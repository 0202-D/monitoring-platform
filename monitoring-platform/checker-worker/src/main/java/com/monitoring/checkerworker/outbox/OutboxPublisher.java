package com.monitoring.checkerworker.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitoring.checkerworker.tracing.TraceContextCarrier;
import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Вторая половина transactional outbox: MonitorJdbcRepository пишет событие
 * в outbox_event в одной транзакции со сменой статуса, а этот класс
 * асинхронно доставляет его в Kafka.
 * Гарантия доставки — AT-LEAST-ONCE, не exactly-once. Сценарий дубля:
 * send() в Kafka подтверждён, но процесс упал (или коммит в БД не прошёл)
 * ДО проставления published_at — при следующем опросе событие уйдёт
 * повторно. Поэтому консьюмеры обязаны быть идемпотентными; ключ
 * идемпотентности — поле eventId внутри события.
 * Трассировка: публикация продолжает трассу проверки, контекст которой
 * сохранён в строке outbox_event (см. TraceContextCarrier). Для этого вся
 * публикация одного события выполняется внутри наблюдения "outbox.publish",
 * созданного как "получатель" с сохранённым контекстом, а отправка в Kafka
 * автоматически становится его дочерним span'ом.
 * TransactionTemplate, а не @Transactional: так граница транзакции видна
 * прямо в коде и не зависит от прокси (вызов @Transactional-метода из
 * соседнего метода того же класса проходит мимо прокси).
 */
@Component
@Slf4j
public class OutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, MonitorStatusEvent> kafkaTemplate;
    private final ObservationRegistry observationRegistry;
    private final TraceContextCarrier traceContextCarrier;

    // Не Spring-бин — по той же причине, что и в MonitorJdbcRepository:
    // автоконфигурация ObjectMapper зависит от spring-web, которого у нас нет
    // в чистом виде для этого сервиса.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${outbox.batch-size:100}")
    private int batchSize;

    @Value("${outbox.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    public OutboxPublisher(JdbcTemplate jdbcTemplate,
                           TransactionTemplate transactionTemplate,
                           KafkaTemplate<String, MonitorStatusEvent> kafkaTemplate,
                           ObservationRegistry observationRegistry,
                           TraceContextCarrier traceContextCarrier) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.observationRegistry = observationRegistry;
        this.traceContextCarrier = traceContextCarrier;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void publishPending() {
        transactionTemplate.executeWithoutResult(status -> {
            // FOR UPDATE SKIP LOCKED — тот же приём, что и в claimDueMonitors:
            // если поднять несколько инстансов checker-worker, они не будут
            // публиковать одни и те же строки одновременно.
            List<OutboxRow> rows = jdbcTemplate.query(
                """
                SELECT id, aggregate_id, event_type, payload::text AS payload, trace_context
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("aggregate_id")),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("trace_context")
                ),
                batchSize
            );

            for (OutboxRow row : rows) {
                if (!publishOne(row)) {
                    // Останавливаем обработку батча на первой неудаче, а не
                    // пропускаем событие и идём дальше: иначе более позднее
                    // событие (например, UP) могло бы обогнать более раннее
                    // (DOWN) для того же монитора. Уже опубликованные до этого
                    // события коммитятся вместе с транзакцией.
                    break;
                }
                jdbcTemplate.update(
                    "UPDATE outbox_event SET published_at = now() WHERE id = ?",
                    row.id()
                );
            }
        });
    }

    private boolean publishOne(OutboxRow row) {
        // Получатель с контекстом из БД: если контекст сохранён, span продолжит
        // трассу проверки, иначе начнётся новая (для событий, записанных без трассировки)
        ReceiverContext<Map<String, String>> context =
            new ReceiverContext<>(Map::get);
        context.setCarrier(traceContextCarrier.parse(row.traceContext()));

        Observation observation = Observation.createNotStarted("outbox.publish", () -> context, observationRegistry)
            .lowCardinalityKeyValue("event.type", row.eventType())
            .highCardinalityKeyValue("monitor.id", row.aggregateId().toString());

        return observation.observe(() -> send(row, observation));
    }

    private boolean send(OutboxRow row, Observation observation) {
        try {
            MonitorStatusEvent event = toAvro(row.payload());

            // Ключ = monitorId: все события одного монитора попадают в одну
            // партицию и читаются консьюмером строго по порядку.
            // .get(timeout) делает отправку синхронной намеренно: published_at
            // можно проставлять только после подтверждения брокером (acks=all),
            // иначе при сбое событие было бы помечено доставленным и потеряно.
            kafkaTemplate
                .send(KafkaTopicConfig.MONITOR_STATUS_EVENTS_TOPIC, row.aggregateId().toString(), event)
                .get(sendTimeoutSeconds, TimeUnit.SECONDS);

            log.info("Published outbox event {} ({}) for monitor {}",
                row.id(), event.getEventType(), row.aggregateId());
            return true;

        } catch (InterruptedException e) {
            observation.error(e);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Kafka недоступна, таймаут, Schema Registry недоступен либо
            // payload не удалось преобразовать. Событие остаётся с
            // published_at = NULL и будет повторено на следующем опросе.
            // Известное ограничение: событие с НЕпреобразуемым payload
            // будет блокировать очередь, пока его не исправят вручную.
            observation.error(e);
            log.error("Failed to publish outbox event {}: {}", row.id(), e.toString());
            return false;
        }
    }

    /**
     * Payload в outbox_event — JSON, записанный MonitorJdbcRepository;
     * здесь он превращается в Avro-объект по схеме из common-events.
     */
    private MonitorStatusEvent toAvro(String payloadJson) throws Exception {
        JsonNode json = objectMapper.readTree(payloadJson);

        String details = json.path("details").asText("");

        return MonitorStatusEvent.newBuilder()
            .setEventId(json.get("eventId").asText())
            .setMonitorId(json.get("monitorId").asText())
            .setUserId(json.get("userId").asText())
            .setEventType(EventType.valueOf(json.get("eventType").asText()))
            .setTargetUrl(json.get("targetUrl").asText())
            .setOccurredAt(Instant.parse(json.get("occurredAt").asText()))
            // В outbox пустая строка означает "деталей нет"; в Avro-схеме
            // поле nullable, поэтому передаём настоящий null.
            .setDetails(details.isEmpty() ? null : details)
            .build();
    }

    private record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload, String traceContext) {
    }
}
