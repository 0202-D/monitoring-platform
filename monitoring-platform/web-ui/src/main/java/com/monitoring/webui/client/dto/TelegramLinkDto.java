package com.monitoring.webui.client.dto;

import java.time.Instant;

/** Зеркалирует TelegramLinkService.TelegramLink из notification-service. */
public record TelegramLinkDto(String url, Instant expiresAt) {
}
