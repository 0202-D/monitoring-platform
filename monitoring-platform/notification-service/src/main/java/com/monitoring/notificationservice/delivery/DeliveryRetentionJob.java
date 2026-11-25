package com.monitoring.notificationservice.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ежедневная очистка: завершённые строки очереди доставки и давно просроченные токены.
 * Ожидающие отправки строки (PENDING) не удаляются никогда.
 *
 * Удаление идёт пачками по первичному ключу: каждая пачка это короткая транзакция,
 * поэтому доставка продолжает работать во время очистки.
 */
@Component
public class DeliveryRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetentionJob.class);

    // Предел на один запуск: страховка от бесконечного цикла, остаток уйдёт в следующий запуск
    private static final int MAX_BATCHES_PER_RUN = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryRetentionProperties properties;
    private final MeterRegistry meterRegistry;

    public DeliveryRetentionJob(JdbcTemplate jdbcTemplate, DeliveryRetentionProperties properties,
                                MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        // Регистрируем сразу, с нулём: иначе ряд появится только после первого удаления
        for (String table : new String[]{"notification_delivery", "telegram_link_token", "email_verification_token"}) {
            Counter.builder("retention.deleted.rows").tag("table", table)
                .description("Строки, удалённые политикой хранения").register(meterRegistry);
        }
    }

    @Scheduled(cron = "${retention.delivery.cron:0 30 3 * * *}")
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        long deliveries = purgeDeliveries();
        long tokens = purgeExpiredTokens();
        log.info("Очистка уведомлений: удалено {} записей очереди и {} просроченных токенов", deliveries, tokens);
    }

    /** SENT и CANCELLED удаляются через sentDays дней, FAILED через failedDays. */
    long purgeDeliveries() {
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = jdbcTemplate.update(
                """
                DELETE FROM notification_delivery
                WHERE id IN (
                    SELECT id
                    FROM notification_delivery
                    WHERE (status IN ('SENT', 'CANCELLED') AND finished_at < now() - (? * interval '1 day'))
                       OR (status = 'FAILED' AND finished_at < now() - (? * interval '1 day'))
                    ORDER BY finished_at
                    LIMIT ?)
                """,
                properties.sentDays(), properties.failedDays(), properties.batchSize());
            total += deleted;
            count("notification_delivery", deleted);
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        return total;
    }

    /**
     * Токены чистятся и при создании нового, но если пользователь больше ничего не запрашивает,
     * просроченные остались бы навсегда. Удаляем те, что истекли больше суток назад.
     */
    long purgeExpiredTokens() {
        int telegram = jdbcTemplate.update(
            "DELETE FROM telegram_link_token WHERE expires_at < now() - interval '1 day'");
        int email = jdbcTemplate.update(
            "DELETE FROM email_verification_token WHERE expires_at < now() - interval '1 day'");
        count("telegram_link_token", telegram);
        count("email_verification_token", email);
        return (long) telegram + email;
    }

    private void count(String table, int deleted) {
        if (deleted > 0) {
            meterRegistry.counter("retention.deleted.rows", "table", table).increment(deleted);
        }
    }
}
