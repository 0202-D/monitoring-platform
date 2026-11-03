package com.monitoring.checkerworker.outbox;

import com.monitoring.checkerworker.support.AbstractPostgresIntegrationTest;
import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Реальная Postgres + замоканный KafkaTemplate: проверяем то, что принадлежит
 * нам (выборка, порядок, пометка published_at, преобразование в Avro),
 * а не работу самой Kafka.
 */
class OutboxPublisherIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String TOPIC = KafkaTopicConfig.MONITOR_STATUS_EVENTS_TOPIC;
    private static final String SUBJECT = "e754da5a-9fb3-47e2-b1f3-68d6163ecc45";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private KafkaTemplate<String, MonitorStatusEvent> kafkaTemplate;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpPublisher() {
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new OutboxPublisher(jdbc, new TransactionTemplate(transactionManager), kafkaTemplate);
        // значения @Value в ручной сборке не подставляются
        ReflectionTestUtils.setField(publisher, "batchSize", 100);
        ReflectionTestUtils.setField(publisher, "sendTimeoutSeconds", 5L);
    }

    @Test
    void publishesPendingEventsInOrderAndMarksThemPublished() {
        UUID monitor1 = UUID.randomUUID();
        UUID monitor2 = UUID.randomUUID();
        UUID monitor3 = UUID.randomUUID();
        insertEvent("e1", monitor1, "MONITOR_DOWN", "boom", 30, false);
        insertEvent("e2", monitor2, "MONITOR_UP", "", 20, false);
        insertEvent("e3", monitor3, "MONITOR_DOWN", "boom", 10, false);
        when(kafkaTemplate.send(anyString(), anyString(), any(MonitorStatusEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MonitorStatusEvent> events = ArgumentCaptor.forClass(MonitorStatusEvent.class);
        verify(kafkaTemplate, times(3)).send(eq(TOPIC), keys.capture(), events.capture());

        assertThat(events.getAllValues()).extracting(MonitorStatusEvent::getEventId)
            .containsExactly("e1", "e2", "e3");
        // ключ = id монитора: события одного монитора попадают в одну партицию
        assertThat(keys.getAllValues())
            .containsExactly(monitor1.toString(), monitor2.toString(), monitor3.toString());
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void convertsPayloadToAvroEvent() {
        UUID monitorId = UUID.randomUUID();
        insertEvent("e1", monitorId, "MONITOR_DOWN", "ConnectException", 20, false);
        insertEvent("e2", monitorId, "MONITOR_UP", "", 10, false);
        when(kafkaTemplate.send(anyString(), anyString(), any(MonitorStatusEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        ArgumentCaptor<MonitorStatusEvent> captor = ArgumentCaptor.forClass(MonitorStatusEvent.class);
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), anyString(), captor.capture());
        List<MonitorStatusEvent> sent = captor.getAllValues();

        MonitorStatusEvent down = sent.get(0);
        assertThat(down.getEventType()).isEqualTo(EventType.MONITOR_DOWN);
        assertThat(down.getMonitorId()).isEqualTo(monitorId.toString());
        assertThat(down.getUserId()).isEqualTo(SUBJECT);
        assertThat(down.getTargetUrl()).isEqualTo("https://example.com");
        assertThat(down.getDetails()).isEqualTo("ConnectException");
        assertThat(down.getOccurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());

        // пустая строка в outbox означает "деталей нет": в Avro это настоящий null
        assertThat(sent.get(1).getEventType()).isEqualTo(EventType.MONITOR_UP);
        assertThat(sent.get(1).getDetails()).isNull();
    }

    @Test
    void stopsAtFirstFailureSoLaterEventsDoNotOvertakeEarlierOnes() {
        insertEvent("e1", UUID.randomUUID(), "MONITOR_DOWN", "boom", 30, false);
        insertEvent("e2", UUID.randomUUID(), "MONITOR_UP", "", 20, false);
        insertEvent("e3", UUID.randomUUID(), "MONITOR_DOWN", "boom", 10, false);
        when(kafkaTemplate.send(anyString(), anyString(), any(MonitorStatusEvent.class)))
            .thenReturn(CompletableFuture.completedFuture(null))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")));

        publisher.publishPending();

        // e3 даже не пытались отправить: иначе он мог бы уйти раньше e2
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), anyString(), any(MonitorStatusEvent.class));
        assertThat(unpublishedEventIds()).containsExactly("e2", "e3");
    }

    @Test
    void retriesUnpublishedEventsOnNextRun() {
        insertEvent("e1", UUID.randomUUID(), "MONITOR_DOWN", "boom", 10, false);
        when(kafkaTemplate.send(anyString(), anyString(), any(MonitorStatusEvent.class)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka down")))
            .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();
        assertThat(unpublishedEventIds()).containsExactly("e1");

        publisher.publishPending();
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void ignoresAlreadyPublishedEvents() {
        insertEvent("e1", UUID.randomUUID(), "MONITOR_DOWN", "boom", 10, true);

        publisher.publishPending();

        verifyNoInteractions(kafkaTemplate);
    }

    // ---------- вспомогательные методы ----------

    private void insertEvent(String eventId, UUID monitorId, String eventType, String details,
                             int ageSeconds, boolean published) {
        String payload = String.format(
            "{\"eventId\":\"%s\",\"monitorId\":\"%s\",\"userId\":\"%s\",\"eventType\":\"%s\","
                + "\"targetUrl\":\"https://example.com\",\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"details\":\"%s\"}",
            eventId, monitorId, SUBJECT, eventType, details);

        jdbc.update(
            "INSERT INTO outbox_event (aggregate_id, event_type, payload, created_at, published_at) "
                + "VALUES (?, ?, ?::jsonb, now() - (? * interval '1 second'), "
                + (published ? "now()" : "NULL") + ")",
            monitorId, eventType, payload, ageSeconds);
    }

    private int unpublishedCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }

    private List<String> unpublishedEventIds() {
        return jdbc.queryForList(
            "SELECT payload->>'eventId' FROM outbox_event WHERE published_at IS NULL ORDER BY created_at",
            String.class);
    }
}
