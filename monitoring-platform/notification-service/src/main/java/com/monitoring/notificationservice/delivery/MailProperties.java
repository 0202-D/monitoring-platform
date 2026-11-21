package com.monitoring.notificationservice.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки писем. Параметры SMTP-сервера (spring.mail.*) задаются переменными
 * окружения SMTP_HOST и т.д.; по умолчанию письма уходят в локальный Mailpit.
 */
@ConfigurationProperties(prefix = "notification.mail")
public record MailProperties(
    // Адрес отправителя. Для настоящей почты его нужно заменить на адрес своего домена
    // или провайдера, иначе письма попадают в спам
    @DefaultValue("no-reply@monitoring.local") String from,
    // Адрес web-ui: на него ведут ссылки в письмах
    @DefaultValue("http://localhost:8083") String webBaseUrl,
    @DefaultValue("24") int verificationTtlHours,
    // Защита от рассылки: повторное письмо подтверждения не чаще, чем раз в это время
    @DefaultValue("60") int resendCooldownSeconds
) {
}
