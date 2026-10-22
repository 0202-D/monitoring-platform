package com.monitoring.checkerworker.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitoring.checkerworker.checks.CheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Slf4j
public class MonitorJdbcRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Сколько неудачных проверок ПОДРЯД нужно, чтобы монитор стал DOWN.
     * Одиночный сбой (потерянный пакет, таймаут, моргнувшая сеть воркера)
     * не должен порождать алерт — иначе нестабильный монитор засыпает
     * пользователя уведомлениями UP/DOWN/UP/DOWN.
     * Цена: при минимальном интервале 60 секунд о реальном падении
     * узнаем примерно через 2–3 минуты. Возврат в UP — сразу.
     */
    @Value("${checker.failure-threshold:3}")
    private int failureThreshold;

    /**
     * Захватывает батч мониторов, которым пора проверяться, и СРАЗУ ЖЕ
     * (в этой же транзакции) отодвигает им next_check_at вперёд — захват
     * задачи (быстрый, под локом) отделён от её выполнения (медленное
     * сетевое I/O, уже без лока на БД). Если бы next_check_at обновлялся
     * только ПОСЛЕ проверки, другой воркер (или следующий цикл опроса)
     * мог бы забрать тот же монитор повторно, пока первая проверка идёт.
     *
     * FOR UPDATE OF m SKIP LOCKED: строки, уже заблокированные другой
     * транзакцией, пропускаются, а не ожидаются. "OF m" обязателен после
     * добавления JOIN: без него PostgreSQL заблокировал бы и строки
     * app_user, и параллельные воркеры начали бы мешать друг другу
     * на пользователях с несколькими мониторами.
     */
    @Transactional
    public List<DueMonitor> claimDueMonitors(int batchSize) {
        List<DueMonitor> claimed = jdbcTemplate.query(
                """
                SELECT m.id, m.user_id, u.keycloak_subject, m.type, m.target_url,
                       m.check_interval_seconds, m.current_status, m.consecutive_failures
                FROM monitor m
                JOIN app_user u ON u.id = m.user_id
                WHERE m.next_check_at <= now() AND m.is_active = true
                ORDER BY m.next_check_at
                LIMIT ?
                FOR UPDATE OF m SKIP LOCKED
                """,
                (rs, rowNum) -> new DueMonitor(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("keycloak_subject"),
                        rs.getString("type"),
                        rs.getString("target_url"),
                        rs.getInt("check_interval_seconds"),
                        rs.getString("current_status"),
                        rs.getInt("consecutive_failures")
                ),
                batchSize
        );

        if (claimed.isEmpty()) {
            return claimed;
        }

        // Отдельный UPDATE на строку: батч небольшой, читается понятнее,
        // чем CASE WHEN по массиву id. Всё в той же транзакции, что и
        // SELECT FOR UPDATE — лок не отпускается между чтением и записью.
        for (DueMonitor monitor : claimed) {
            jdbcTemplate.update(
                    """
                    UPDATE monitor
                    SET next_check_at = now() + (check_interval_seconds * interval '1 second')
                    WHERE id = ?
                    """,
                    monitor.id()
            );
        }

        log.debug("Claimed {} monitors for checking", claimed.size());
        return claimed;
    }

    /**
     * Записывает результат проверки: check_result (история), monitor
     * (текущий статус и счётчик неудач) и, только если статус реально
     * изменился, outbox_event. Всё одной транзакцией — это и есть
     * transactional outbox: событие не потеряется и не будет опубликовано
     * для откатившегося изменения.
     */
    @Transactional
    public void recordCheckOutcome(DueMonitor monitor, CheckResult result) {
        jdbcTemplate.update(
                """
                INSERT INTO check_result (monitor_id, checked_at, status, response_time_ms, status_code, error_message)
                VALUES (?, now(), ?, ?, ?, ?)
                """,
                monitor.id(), result.status().name(), result.responseTimeMs(),
                result.statusCode(), result.errorMessage()
        );

        boolean checkFailed = result.status() == CheckResult.Status.DOWN;
        int newConsecutiveFailures = checkFailed ? monitor.consecutiveFailures() + 1 : 0;

        // В check_result всегда пишем фактический результат проверки, а вот
        // ОФИЦИАЛЬНЫЙ статус монитора меняется только после подтверждения:
        //   успех                        -> UP сразу
        //   сбой, но порог не достигнут  -> статус остаётся прежним
        //   сбой, порог достигнут        -> DOWN
        String newStatus;
        if (!checkFailed) {
            newStatus = "UP";
        } else if (newConsecutiveFailures >= failureThreshold) {
            newStatus = "DOWN";
        } else {
            newStatus = monitor.currentStatus();
        }

        jdbcTemplate.update(
                """
                UPDATE monitor
                SET current_status = ?, consecutive_failures = ?
                WHERE id = ?
                """,
                newStatus, newConsecutiveFailures, monitor.id()
        );

        boolean statusChanged = !newStatus.equals(monitor.currentStatus());
        if (statusChanged) {
            insertOutboxEvent(monitor, newStatus, result.errorMessage());
        }
    }

    private void insertOutboxEvent(DueMonitor monitor, String newStatus, String details) {
        String eventType = newStatus.equals("UP") ? "MONITOR_UP" : "MONITOR_DOWN";

        Map<String, Object> payload = Map.of(
                "eventId", UUID.randomUUID().toString(),
                "monitorId", monitor.id().toString(),
                // Keycloak sub, а не app_user.id: это общий идентификатор
                // пользователя для всех сервисов (notification-service
                // привязывает каналы уведомлений именно к нему)
                "userId", monitor.userSubject(),
                "eventType", eventType,
                "targetUrl", monitor.targetUrl(),
                "occurredAt", Instant.now().toString(),
                "details", details == null ? "" : details
        );

        try {
            PGobject jsonPayload = new PGobject();
            jsonPayload.setType("jsonb");
            jsonPayload.setValue(objectMapper.writeValueAsString(payload));

            jdbcTemplate.update(
                    "INSERT INTO outbox_event (aggregate_id, event_type, payload) VALUES (?, ?, ?)",
                    monitor.id(), eventType, jsonPayload
            );

            log.info("Monitor {} changed status to {} — outbox event recorded", monitor.id(), newStatus);
        } catch (Exception e) {
            // При сбое откатится вся транзакция (включая check_result и
            // monitor выше), а не останется рассинхронизированное состояние.
            throw new IllegalStateException("Не удалось сериализовать outbox payload", e);
        }
    }
}