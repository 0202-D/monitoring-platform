package com.monitoring.webui.client.dto;

import java.time.Instant;

/**
 * Зеркалирует UserChannelRepository.ChannelInfo из notification-service.
 * enabled — выбор пользователя; status (ACTIVE / BROKEN) — состояние системы;
 * active = enabled && status == ACTIVE.
 */
public record ChannelDto(String channelType, boolean enabled, String status,
                         String statusReason, boolean active, Instant createdAt) {
}
