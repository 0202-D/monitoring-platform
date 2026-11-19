package com.monitoring.notificationservice.delivery;

import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.channel.UserChannelRepository.ChannelInfo;
import com.monitoring.notificationservice.delivery.DeliveryRepository.NewDelivery;
import com.monitoring.notificationservice.tracing.TraceContextCarrier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryPlannerTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final long OCCURRED_AT = 1_700_000_000_000L;

    @Mock
    private UserChannelRepository channelRepository;
    @Mock
    private DeliveryRepository deliveryRepository;

    private SimpleMeterRegistry registry;
    private DeliveryPlanner planner;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ChannelSender telegram = new StubSender("TELEGRAM");
        planner = new DeliveryPlanner(
            channelRepository, deliveryRepository, List.of(telegram),
            new DeliveryMetrics(registry, List.of(telegram)),
            new TraceContextCarrier(Tracer.NOOP, Propagator.NOOP));
    }

    @Test
    void plansDeliveryForEnabledActiveChannel() {
        when(channelRepository.findByUserId(USER_ID)).thenReturn(List.of(channel(true, "ACTIVE")));
        when(deliveryRepository.insertIfAbsent(any())).thenReturn(true);

        planner.plan(event(USER_ID.toString()));

        ArgumentCaptor<NewDelivery> captor = ArgumentCaptor.forClass(NewDelivery.class);
        verify(deliveryRepository).insertIfAbsent(captor.capture());
        NewDelivery delivery = captor.getValue();
        assertThat(delivery.eventId()).isEqualTo("event-1");
        assertThat(delivery.channelType()).isEqualTo("TELEGRAM");
        assertThat(delivery.userId()).isEqualTo(USER_ID);
        assertThat(delivery.monitorId()).isEqualTo("monitor-1");
        assertThat(delivery.eventType()).isEqualTo("MONITOR_DOWN");
        assertThat(delivery.targetUrl()).isEqualTo("https://example.com");
        assertThat(delivery.details()).isEqualTo("ConnectException");
        assertThat(delivery.occurredAt()).isEqualTo(Instant.ofEpochMilli(OCCURRED_AT));
        assertThat(count("alerts.planned", "channel", "telegram")).isEqualTo(1.0);
    }

    @Test
    void skipsChannelDisabledByUser() {
        when(channelRepository.findByUserId(USER_ID)).thenReturn(List.of(channel(false, "ACTIVE")));

        planner.plan(event(USER_ID.toString()));

        verify(deliveryRepository, never()).insertIfAbsent(any());
        assertThat(count("alerts.skipped", "reason", "channel_disabled")).isEqualTo(1.0);
    }

    @Test
    void skipsBrokenChannelEvenIfUserEnabledIt() {
        when(channelRepository.findByUserId(USER_ID)).thenReturn(List.of(channel(true, "BROKEN")));

        planner.plan(event(USER_ID.toString()));

        verify(deliveryRepository, never()).insertIfAbsent(any());
        assertThat(count("alerts.skipped", "reason", "channel_broken")).isEqualTo(1.0);
    }

    @Test
    void skipsUserWithoutChannel() {
        when(channelRepository.findByUserId(USER_ID)).thenReturn(List.of());

        planner.plan(event(USER_ID.toString()));

        verify(deliveryRepository, never()).insertIfAbsent(any());
        assertThat(count("alerts.skipped", "reason", "no_channel")).isEqualTo(1.0);
    }

    @Test
    void countsRepeatedEventAsDuplicate() {
        // Повторная доставка того же события из Kafka: уникальный ключ не даёт создать вторую строку
        when(channelRepository.findByUserId(USER_ID)).thenReturn(List.of(channel(true, "ACTIVE")));
        when(deliveryRepository.insertIfAbsent(any())).thenReturn(false);

        planner.plan(event(USER_ID.toString()));

        assertThat(count("alerts.skipped", "reason", "duplicate")).isEqualTo(1.0);
        assertThat(count("alerts.planned", "channel", "telegram")).isZero();
    }

    @Test
    void skipsEventWithInvalidUserIdWithoutThrowing() {
        planner.plan(event("not-a-uuid"));

        verifyNoInteractions(channelRepository, deliveryRepository);
        assertThat(count("alerts.skipped", "reason", "invalid_user_id")).isEqualTo(1.0);
    }

    private double count(String name, String tagKey, String tagValue) {
        return registry.counter(name, tagKey, tagValue).count();
    }

    private ChannelInfo channel(boolean enabled, String status) {
        return new ChannelInfo("TELEGRAM", enabled, status, null, enabled && "ACTIVE".equals(status), Instant.now());
    }

    private MonitorStatusEvent event(String userId) {
        return MonitorStatusEvent.newBuilder()
            .setEventId("event-1")
            .setMonitorId("monitor-1")
            .setUserId(userId)
            .setEventType(EventType.MONITOR_DOWN)
            .setTargetUrl("https://example.com")
            .setOccurredAt(OCCURRED_AT)
            .setDetails("ConnectException")
            .build();
    }

    static class StubSender implements ChannelSender {
        private final String type;
        RuntimeException failure;
        String lastDestination;

        StubSender(String type) {
            this.type = type;
        }

        @Override
        public String channelType() {
            return type;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(String destination, MonitorStatusEvent event) {
            lastDestination = destination;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
