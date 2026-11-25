package com.monitoring.notificationservice.delivery;

import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.support.AbstractPostgresIntegrationTest;
import com.monitoring.notificationservice.tracing.TraceContextCarrier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной путь на настоящей БД: планировщик (то, что делает консьюмер Kafka) и воркер
 * доставки с настоящими репозиториями. Меняется только сам канал: вместо Telegram
 * сценарный отправитель, которому можно заранее задать сбои.
 */
class DeliveryFlowIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final DeliveryProperties PROPERTIES = new DeliveryProperties(20, 10, 10, 600, 120);

    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private UserChannelRepository channelRepository;

    private ScriptedSender sender;
    private DeliveryPlanner planner;
    private DeliveryWorker worker;

    @BeforeEach
    void setUp() {
        sender = new ScriptedSender();
        DeliveryMetrics metrics = new DeliveryMetrics(new SimpleMeterRegistry(), List.of(sender));
        TraceContextCarrier carrier = new TraceContextCarrier(Tracer.NOOP, Propagator.NOOP);
        planner = new DeliveryPlanner(channelRepository, deliveryRepository, List.of(sender), metrics, carrier);
        worker = new DeliveryWorker(deliveryRepository, channelRepository, List.of(sender), metrics,
            ObservationRegistry.NOOP, carrier, PROPERTIES);

        channelRepository.upsert(USER_ID, "TELEGRAM", "555");
    }

    @Test
    void plannedEventIsDeliveredToTheChannelDestination() {
        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));

        assertThat(statusOf("e1")).isEqualTo("PENDING");

        worker.deliverDue();

        assertThat(sender.successful).containsExactly("e1");
        assertThat(sender.destinations).containsExactly("555");
        assertThat(statusOf("e1")).isEqualTo("SENT");
    }

    @Test
    void repeatedKafkaDeliveryOfSameEventIsSentOnlyOnce() {
        MonitorStatusEvent event = event("e1", "m1", EventType.MONITOR_DOWN);

        planner.plan(event);
        planner.plan(event);
        worker.deliverDue();
        worker.deliverDue();

        assertThat(sender.successful).containsExactly("e1");
    }

    @Test
    void transientFailureIsRetriedUntilDelivered() {
        sender.failures.add(new DeliveryException("timeout", false));
        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));

        worker.deliverDue();

        assertThat(statusOf("e1")).isEqualTo("PENDING");
        assertThat(attemptsOf("e1")).isEqualTo(1);
        assertThat(sender.successful).isEmpty();

        makePendingRowsDue();
        worker.deliverDue();

        assertThat(statusOf("e1")).isEqualTo("SENT");
        assertThat(attemptsOf("e1")).isEqualTo(2);
        assertThat(sender.successful).containsExactly("e1");
    }

    @Test
    void laterEventOfSameMonitorDoesNotOvertakeEarlierOneThatIsWaitingForRetry() {
        sender.failures.add(new DeliveryException("timeout", false));
        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));
        planner.plan(event("e2", "m1", EventType.MONITOR_UP));

        worker.deliverDue();   // e1 не удалась и ждёт повтора; e2 не берётся
        worker.deliverDue();   // e1 ещё не наступила, e2 по-прежнему ждёт её
        assertThat(sender.attempted).containsExactly("e1");

        makePendingRowsDue();
        worker.deliverDue();   // e1 доставлена
        worker.deliverDue();   // теперь очередь e2

        assertThat(sender.successful).containsExactly("e1", "e2");
    }

    @Test
    void permanentFailureFailsDeliveryAndMarksChannelBroken() {
        sender.failures.add(new DeliveryException("HTTP 403", true));
        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));

        worker.deliverDue();

        assertThat(statusOf("e1")).isEqualTo("FAILED");
        UserChannelRepository.ChannelInfo channel = channelRepository.findOne(USER_ID, "TELEGRAM").orElseThrow();
        assertThat(channel.status()).isEqualTo("BROKEN");
        assertThat(channel.active()).isFalse();
    }

    @Test
    void channelDisabledWhileRowIsWaitingCancelsDelivery() {
        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));

        // Пользователь выключил переключатель между постановкой в очередь и отправкой
        channelRepository.setEnabled(USER_ID, "TELEGRAM", false);
        worker.deliverDue();

        assertThat(statusOf("e1")).isEqualTo("CANCELLED");
        assertThat(sender.attempted).isEmpty();
    }

    @Test
    void disabledChannelGetsNoRowsAtAll() {
        channelRepository.setEnabled(USER_ID, "TELEGRAM", false);

        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));

        assertThat(rowCount()).isZero();
    }

    @Test
    void exhaustedRetriesEndWithFailedRow() {
        DeliveryProperties twoAttempts = new DeliveryProperties(20, 2, 10, 600, 120);
        DeliveryMetrics metrics = new DeliveryMetrics(new SimpleMeterRegistry(), List.of(sender));
        DeliveryWorker shortWorker = new DeliveryWorker(deliveryRepository, channelRepository, List.of(sender),
            metrics, ObservationRegistry.NOOP, new TraceContextCarrier(Tracer.NOOP, Propagator.NOOP), twoAttempts);
        sender.failures.add(new DeliveryException("timeout", false));
        sender.failures.add(new DeliveryException("timeout", false));
        planner.plan(event("e1", "m1", EventType.MONITOR_DOWN));

        shortWorker.deliverDue();
        makePendingRowsDue();
        shortWorker.deliverDue();

        assertThat(statusOf("e1")).isEqualTo("FAILED");
        assertThat(attemptsOf("e1")).isEqualTo(2);
        // Сам канал при этом исправен: Telegram просто временно не отвечал
        assertThat(channelRepository.findOne(USER_ID, "TELEGRAM").orElseThrow().status()).isEqualTo("ACTIVE");
    }

    // ---------- вспомогательные методы ----------

    private void makePendingRowsDue() {
        jdbc.update("UPDATE notification_delivery SET next_attempt_at = now() WHERE status = 'PENDING'");
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("SELECT status FROM notification_delivery WHERE event_id = ?", String.class, eventId);
    }

    private int attemptsOf(String eventId) {
        return jdbc.queryForObject("SELECT attempts FROM notification_delivery WHERE event_id = ?", Integer.class, eventId);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Integer.class);
    }

    private MonitorStatusEvent event(String eventId, String monitorId, EventType type) {
        return MonitorStatusEvent.newBuilder()
            .setEventId(eventId)
            .setMonitorId(monitorId)
            .setUserId(USER_ID.toString())
            .setEventType(type)
            .setTargetUrl("https://example.com")
            .setOccurredAt(Instant.ofEpochMilli(1_700_000_000_000L))
            .setDetails(type == EventType.MONITOR_DOWN ? "ConnectException" : null)
            .build();
    }

    /** Отправитель с заданными заранее сбоями: каждая отправка берёт следующий сбой из очереди. */
    static class ScriptedSender implements ChannelSender {
        final Queue<RuntimeException> failures = new ArrayDeque<>();
        final List<String> attempted = new ArrayList<>();
        final List<String> successful = new ArrayList<>();
        final List<String> destinations = new ArrayList<>();

        @Override
        public String channelType() {
            return "TELEGRAM";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public synchronized void send(String destination, MonitorStatusEvent event) {
            attempted.add(event.getEventId());
            RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            successful.add(event.getEventId());
            destinations.add(destination);
        }
    }
}
