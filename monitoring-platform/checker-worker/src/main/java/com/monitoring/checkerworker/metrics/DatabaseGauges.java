package com.monitoring.checkerworker.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Метрики-"снимки" состояния данных. Gauge вычисляется в момент опроса
 * Prometheus (раз в 15 секунд): это 5 дешёвых запросов (для неопубликованных
 * событий есть частичный индекс idx_outbox_unpublished).
 *
 * Если запрос не удался, возвращается NaN: Prometheus покажет разрыв на
 * графике, а не ложный ноль, который выглядел бы как "всё в порядке".
 */
@Component
public class DatabaseGauges {

    private static final List<String> MONITOR_STATUSES = List.of("UNKNOWN", "UP", "DOWN");

    public DatabaseGauges(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        Gauge.builder("outbox.events.unpublished", jdbcTemplate,
                jdbc -> query(jdbc, "SELECT count(*) FROM outbox_event WHERE published_at IS NULL"))
            .description("События в outbox, ещё не опубликованные в Kafka")
            .register(registry);

        // Растущее значение означает, что публикация в Kafka застряла
        Gauge.builder("outbox.oldest.unpublished.age", jdbcTemplate,
                jdbc -> query(jdbc, "SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) "
                    + "FROM outbox_event WHERE published_at IS NULL"))
            .baseUnit("seconds")
            .description("Возраст самого старого неопубликованного события outbox")
            .register(registry);

        for (String status : MONITOR_STATUSES) {
            Gauge.builder("monitors.count", jdbcTemplate,
                    jdbc -> query(jdbc, "SELECT count(*) FROM monitor "
                        + "WHERE is_active = true AND current_status = '" + status + "'"))
                .tag("status", status)
                .description("Активные мониторы по текущему статусу")
                .register(registry);
        }
    }

    private static double query(JdbcTemplate jdbcTemplate, String sql) {
        try {
            Number value = jdbcTemplate.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
