package com.monitoring.notificationservice.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Политика хранения очереди доставки. Время запуска задаёт retention.delivery.cron. */
@ConfigurationProperties(prefix = "retention.delivery")
public record DeliveryRetentionProperties(
    @DefaultValue("true") boolean enabled,
    // Успешные и отменённые доставки нужны недолго
    @DefaultValue("30") int sentDays,
    // Неудачные хранятся дольше: по ним разбираются, почему уведомление не дошло
    @DefaultValue("90") int failedDays,
    @DefaultValue("5000") int batchSize
) {
}
