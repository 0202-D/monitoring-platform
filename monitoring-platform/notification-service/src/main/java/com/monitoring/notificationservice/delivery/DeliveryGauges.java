package com.monitoring.notificationservice.delivery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Снимки состояния очереди, вычисляются в момент опроса Prometheus (раз в 15 секунд).
 * Если запрос не удался, возвращается NaN: на графике будет разрыв, а не ложный ноль.
 */
@Component
public class DeliveryGauges {

    public DeliveryGauges(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        Gauge.builder("notification.queue.pending", jdbcTemplate,
                jdbc -> query(jdbc, "SELECT count(*) FROM notification_delivery WHERE status = 'PENDING'"))
            .description("Уведомления в очереди, ожидающие отправки или повтора")
            .register(registry);

        Gauge.builder("notification.queue.oldest.pending.age", jdbcTemplate,
                jdbc -> query(jdbc, "SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) "
                    + "FROM notification_delivery WHERE status = 'PENDING'"))
            .baseUnit("seconds")
            .description("Возраст самого старого уведомления, ожидающего отправки")
            .register(registry);

        Gauge.builder("notification.queue.failed.recent", jdbcTemplate,
                jdbc -> query(jdbc, "SELECT count(*) FROM notification_delivery "
                    + "WHERE status = 'FAILED' AND finished_at > now() - interval '24 hours'"))
            .description("Недоставленные уведомления за последние 24 часа")
            .register(registry);
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
