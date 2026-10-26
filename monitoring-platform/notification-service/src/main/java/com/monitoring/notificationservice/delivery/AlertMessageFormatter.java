package com.monitoring.notificationservice.delivery;

import com.monitoring.events.MonitorStatusEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class AlertMessageFormatter {

    // Часовой пояс пользователя сервису неизвестен, поэтому время в UTC
    // с явной пометкой. Персональный часовой пояс — отдельное улучшение.
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    // Telegram ограничивает сообщение 4096 символами; текст ошибки обрезаем
    // заранее, чтобы длинный стектрейс не превратил алерт в 400 Bad Request.
    private static final int MAX_DETAILS_LENGTH = 300;

    public String format(MonitorStatusEvent event) {
        StringBuilder text = new StringBuilder();

        switch (event.getEventType()) {
            case MONITOR_DOWN -> text.append("🔴 Монитор недоступен");
            case MONITOR_UP -> text.append("🟢 Монитор снова доступен");
            default -> text.append("ℹ️ Событие монитора: ").append(event.getEventType());
        }

        text.append('\n').append(event.getTargetUrl());

        String details = event.getDetails();
        if (details != null && !details.isBlank()) {
            text.append("\nПричина: ").append(truncate(details));
        }

        text.append("\nВремя: ").append(TIME_FORMAT.format(Instant.ofEpochMilli(event.getOccurredAt())));
        return text.toString();
    }

    private String truncate(String value) {
        return value.length() <= MAX_DETAILS_LENGTH ? value : value.substring(0, MAX_DETAILS_LENGTH) + "…";
    }
}
