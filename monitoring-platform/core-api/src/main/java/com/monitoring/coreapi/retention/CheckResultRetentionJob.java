package com.monitoring.coreapi.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ежедневная очистка истории проверок старше срока хранения.
 *
 * Таблица check_result объявлена секционированной, но на деле в ней одна секция
 * по умолчанию (см. миграцию 003), поэтому сбросить старые данные целой секцией нечем.
 * Удаляем пачками: каждая пачка это короткая транзакция, поэтому проверки продолжают
 * записываться, а блокировки не копятся. Пока объём невелик, этого достаточно;
 * при росте до десятков миллионов строк в месяц стоит перейти на настоящие секции по месяцам.
 *
 * Строки в пачке выбираются по первичному ключу (id, checked_at), а не по ctid: у каждой
 * секции своя нумерация ctid, и после появления секций такое удаление задело бы чужие строки.
 *
 * Если запущено несколько экземпляров core-api, очистка идёт параллельно: это безвредно,
 * удаление идемпотентно (вторая попытка просто не находит уже удалённых строк).
 */
@Component
public class CheckResultRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(CheckResultRetentionJob.class);

    // Предел на один запуск: страховка от бесконечного цикла, остаток уйдёт в следующий запуск
    private static final int MAX_BATCHES_PER_RUN = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final CheckResultRetentionProperties properties;
    private final Counter deletedRows;

    public CheckResultRetentionJob(JdbcTemplate jdbcTemplate,
                                   CheckResultRetentionProperties properties,
                                   MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        // Регистрируем сразу, с нулём: иначе ряд появится только после первого удаления
        this.deletedRows = Counter.builder("retention.deleted.rows")
            .tag("table", "check_result")
            .description("Строки, удалённые политикой хранения")
            .register(meterRegistry);
    }

    @Scheduled(cron = "${retention.check-result.cron:0 15 3 * * *}")
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        if (properties.days() <= 0) {
            log.warn("retention.check-result.days = {}: очистка истории проверок отключена", properties.days());
            return;
        }
        long deleted = purgeOlderThanDays(properties.days());
        log.info("Очистка истории проверок: удалено {} строк старше {} дней", deleted, properties.days());
    }

    /** Удаляет пачками все проверки старше указанного числа дней и возвращает число удалённых строк. */
    long purgeOlderThanDays(int days) {
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = jdbcTemplate.update(
                """
                DELETE FROM check_result
                WHERE (id, checked_at) IN (
                    SELECT id, checked_at
                    FROM check_result
                    WHERE checked_at < now() - (? * interval '1 day')
                    ORDER BY checked_at
                    LIMIT ?)
                """,
                days, properties.batchSize());
            total += deleted;
            deletedRows.increment(deleted);
            if (deleted < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
