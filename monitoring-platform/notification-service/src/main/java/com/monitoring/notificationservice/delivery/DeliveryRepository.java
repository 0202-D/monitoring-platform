package com.monitoring.notificationservice.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
public class DeliveryRepository {

    private static final int MAX_ERROR_LENGTH = 500;

    public record NewDelivery(String eventId, String channelType, UUID userId, String monitorId,
                              String eventType, String targetUrl, String details,
                              Instant occurredAt, String traceContext) {
    }

    public record Delivery(UUID id, String eventId, String channelType, UUID userId, String monitorId,
                           String eventType, String targetUrl, String details, Instant occurredAt,
                           int attempts, String traceContext) {

        Delivery withAttempts(int newAttempts) {
            return new Delivery(id, eventId, channelType, userId, monitorId, eventType, targetUrl,
                details, occurredAt, newAttempts, traceContext);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    public DeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * ON CONFLICT DO NOTHING на уникальном (event_id, channel_type): повторная доставка
     * того же события из Kafka не создаёт вторую строку. Возвращает false, если строка уже была.
     */
    public boolean insertIfAbsent(NewDelivery d) {
        return jdbcTemplate.update(
            """
            INSERT INTO notification_delivery
                (event_id, channel_type, user_id, monitor_id, event_type, target_url, details, occurred_at, trace_context)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id, channel_type) DO NOTHING
            """,
            d.eventId(), d.channelType(), d.userId(), d.monitorId(), d.eventType(), d.targetUrl(),
            d.details(), Timestamp.from(d.occurredAt()), d.traceContext()
        ) > 0;
    }

    /**
     * Захватывает строки, которые пора отправлять, и сразу "арендует" их: сдвигает
     * next_attempt_at на leaseSeconds и увеличивает attempts. Захват (быстрая транзакция)
     * отделён от самой отправки (медленный сетевой вызов), как в claimDueMonitors.

     * Порядок: строку берём, только если для той же пары (монитор, канал) нет более
     * старой неотправленной строки, иначе уведомление UP могло бы обогнать DOWN.
     * Блокировка "OF d" берёт только строки очереди, а не подзапроса.
     */
    @Transactional
    public List<Delivery> claimDue(int batchSize, int leaseSeconds, List<String> channelTypes) {
        if (channelTypes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(channelTypes.size(), "?"));

        Object[] args = new Object[channelTypes.size() + 1];
        for (int i = 0; i < channelTypes.size(); i++) {
            args[i] = channelTypes.get(i);
        }
        args[channelTypes.size()] = batchSize;

        List<Delivery> due = jdbcTemplate.query(
            """
            SELECT d.id, d.event_id, d.channel_type, d.user_id, d.monitor_id, d.event_type,
                   d.target_url, d.details, d.occurred_at, d.attempts, d.trace_context
            FROM notification_delivery d
            WHERE d.status = 'PENDING'
              AND d.next_attempt_at <= now()
              AND d.channel_type IN (%s)
              AND NOT EXISTS (
                  SELECT 1 FROM notification_delivery o
                  WHERE o.monitor_id = d.monitor_id
                    AND o.channel_type = d.channel_type
                    AND o.status = 'PENDING'
                    AND o.seq < d.seq)
            ORDER BY d.seq
            LIMIT ?
            FOR UPDATE OF d SKIP LOCKED
            """.formatted(placeholders),
            (rs, rowNum) -> new Delivery(
                UUID.fromString(rs.getString("id")),
                rs.getString("event_id"),
                rs.getString("channel_type"),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("monitor_id"),
                rs.getString("event_type"),
                rs.getString("target_url"),
                rs.getString("details"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getInt("attempts"),
                rs.getString("trace_context")),
            args
        );

        for (Delivery delivery : due) {
            jdbcTemplate.update(
                """
                UPDATE notification_delivery
                SET attempts = attempts + 1, next_attempt_at = now() + (? * interval '1 second')
                WHERE id = ?
                """,
                leaseSeconds, delivery.id());
        }
        return due.stream().map(d -> d.withAttempts(d.attempts() + 1)).toList();
    }

    public void markSent(UUID id) {
        jdbcTemplate.update(
            "UPDATE notification_delivery SET status = 'SENT', last_error = NULL, finished_at = now() WHERE id = ?",
            id);
    }

    public void markFailed(UUID id, String error) {
        jdbcTemplate.update(
            "UPDATE notification_delivery SET status = 'FAILED', last_error = ?, finished_at = now() WHERE id = ?",
            truncate(error), id);
    }

    public void markCancelled(UUID id, String reason) {
        jdbcTemplate.update(
            "UPDATE notification_delivery SET status = 'CANCELLED', last_error = ?, finished_at = now() WHERE id = ?",
            truncate(reason), id);
    }

    public void scheduleRetry(UUID id, long delaySeconds, String error) {
        jdbcTemplate.update(
            """
            UPDATE notification_delivery
            SET next_attempt_at = now() + (? * interval '1 second'), last_error = ?
            WHERE id = ?
            """,
            delaySeconds, truncate(error), id);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
