package com.monitoring.notificationservice.delivery;

import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.delivery.DeliveryPlannerTest.StubSender;
import com.monitoring.notificationservice.delivery.DeliveryRepository.Delivery;
import com.monitoring.notificationservice.tracing.TraceContextCarrier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryWorkerTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final UUID DELIVERY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    // batchSize, maxAttempts, initialBackoffSeconds, maxBackoffSeconds, leaseSeconds
    private static final DeliveryProperties PROPERTIES = new DeliveryProperties(20, 10, 10, 600, 120);

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private UserChannelRepository channelRepository;

    private StubSender sender;
    private SimpleMeterRegistry registry;
    private DeliveryWorker worker;

    @BeforeEach
    void setUp() {
        sender = new StubSender("TELEGRAM");
        registry = new SimpleMeterRegistry();
        worker = new DeliveryWorker(
            deliveryRepository, channelRepository, List.of(sender),
            new DeliveryMetrics(registry, List.of(sender)),
            ObservationRegistry.NOOP,
            new TraceContextCarrier(Tracer.NOOP, Propagator.NOOP),
            PROPERTIES);
    }

    @Test
    void deliversAndMarksSent() {
        when(channelRepository.findActiveDestination(USER_ID, "TELEGRAM")).thenReturn(Optional.of("555"));

        worker.process(delivery(1));

        verify(deliveryRepository).markSent(DELIVERY_ID);
        assertThat(sender.lastDestination).isEqualTo("555");
        assertThat(count("alerts.delivered", "channel", "telegram")).isEqualTo(1.0);
    }

    @Test
    void cancelsWhenChannelIsNoLongerAvailable() {
        // Пользователь успел выключить канал, пока строка ждала в очереди
        when(channelRepository.findActiveDestination(USER_ID, "TELEGRAM")).thenReturn(Optional.empty());

        worker.process(delivery(1));

        verify(deliveryRepository).markCancelled(eq(DELIVERY_ID), anyString());
        assertThat(sender.lastDestination).isNull();
        assertThat(count("alerts.skipped", "reason", "cancelled")).isEqualTo(1.0);
    }

    @Test
    void permanentFailureMarksDeliveryFailedAndChannelBroken() {
        when(channelRepository.findActiveDestination(USER_ID, "TELEGRAM")).thenReturn(Optional.of("555"));
        sender.failure = new DeliveryException("HTTP 403", true);

        worker.process(delivery(1));

        verify(deliveryRepository).markFailed(DELIVERY_ID, "HTTP 403");
        verify(channelRepository).deactivate(USER_ID, "TELEGRAM");
        verify(deliveryRepository, never()).scheduleRetry(DELIVERY_ID, 10L, "HTTP 403");
        assertThat(count("alerts.failed", "channel", "telegram", "kind", "permanent")).isEqualTo(1.0);
    }

    @Test
    void transientFailureSchedulesRetryWithExponentialBackoff() {
        when(channelRepository.findActiveDestination(USER_ID, "TELEGRAM")).thenReturn(Optional.of("555"));
        sender.failure = new DeliveryException("timeout", false);

        worker.process(delivery(3));

        // третья попытка: 10 * 2^(3-1) = 40 секунд
        verify(deliveryRepository).scheduleRetry(DELIVERY_ID, 40L, "timeout");
        verify(deliveryRepository, never()).markFailed(eq(DELIVERY_ID), anyString());
        verify(channelRepository, never()).deactivate(USER_ID, "TELEGRAM");
        assertThat(count("alerts.failed", "channel", "telegram", "kind", "transient")).isEqualTo(1.0);
    }

    @Test
    void exhaustedAttemptsMarkDeliveryFailedWithoutRetry() {
        when(channelRepository.findActiveDestination(USER_ID, "TELEGRAM")).thenReturn(Optional.of("555"));
        sender.failure = new DeliveryException("timeout", false);

        worker.process(delivery(10));

        verify(deliveryRepository).markFailed(DELIVERY_ID, "timeout");
        verify(deliveryRepository, never()).scheduleRetry(eq(DELIVERY_ID), anyLong(), anyString());
        // сам канал не ломаем: он исправен, просто Telegram временно не отвечал
        verify(channelRepository, never()).deactivate(USER_ID, "TELEGRAM");
        assertThat(count("alerts.undelivered", "channel", "telegram")).isEqualTo(1.0);
    }

    @Test
    void unexpectedExceptionIsTreatedAsTransient() {
        when(channelRepository.findActiveDestination(USER_ID, "TELEGRAM")).thenReturn(Optional.of("555"));
        sender.failure = new IllegalStateException("boom");

        worker.process(delivery(1));

        verify(deliveryRepository).scheduleRetry(eq(DELIVERY_ID), eq(10L), anyString());
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(DeliveryWorker.backoffSeconds(1, PROPERTIES)).isEqualTo(10);
        assertThat(DeliveryWorker.backoffSeconds(2, PROPERTIES)).isEqualTo(20);
        assertThat(DeliveryWorker.backoffSeconds(4, PROPERTIES)).isEqualTo(80);
        // 10 * 2^7 = 1280, но не больше максимума
        assertThat(DeliveryWorker.backoffSeconds(8, PROPERTIES)).isEqualTo(600);
        assertThat(DeliveryWorker.backoffSeconds(40, PROPERTIES)).isEqualTo(600);
    }

    private double count(String name, String... tags) {
        return registry.counter(name, tags).count();
    }

    private Delivery delivery(int attempts) {
        return new Delivery(DELIVERY_ID, "event-1", "TELEGRAM", USER_ID, "monitor-1", "MONITOR_DOWN",
            "https://example.com", "ConnectException", Instant.ofEpochMilli(1_700_000_000_000L), attempts, null);
    }
}
