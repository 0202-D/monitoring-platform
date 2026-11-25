package com.monitoring.coreapi.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Политика хранения истории проверок. Время запуска задаёт retention.check-result.cron. */
@ConfigurationProperties(prefix = "retention.check-result")
public record CheckResultRetentionProperties(
    @DefaultValue("true") boolean enabled,
    // Сколько дней хранить историю проверок. Значение 0 или меньше отключает очистку
    @DefaultValue("30") int days,
    // Размер одной пачки удаления: короткие транзакции не блокируют таблицу надолго
    @DefaultValue("10000") int batchSize
) {
}
