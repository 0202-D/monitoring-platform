package com.monitoring.notificationservice.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки воркера доставки. При значениях по умолчанию повторы идут с паузами
 * 10, 20, 40, 80, 160, 320, 600, 600, 600 секунд: около 40 минут на 10 попыток.
 */
@ConfigurationProperties(prefix = "delivery")
public record DeliveryProperties(
    @DefaultValue("20") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("10") int initialBackoffSeconds,
    @DefaultValue("600") int maxBackoffSeconds,
    // Аренда строки на время отправки: если воркер упал, через это время строку возьмёт другой
    @DefaultValue("120") int leaseSeconds
) {
}
