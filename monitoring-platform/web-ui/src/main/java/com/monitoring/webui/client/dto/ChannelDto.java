package com.monitoring.webui.client.dto;

import java.time.Instant;

/**
 * Зеркалирует UserChannelRepository.ChannelInfo из notification-service.
 * enabled — выбор пользователя; status (ACTIVE / BROKEN / PENDING_VERIFICATION) — состояние
 * системы; active = enabled && status == ACTIVE; destinationHint — замаскированный адрес
 * (только для email).
 */
public record ChannelDto(String channelType, boolean enabled, String status, String statusReason,
                         boolean active, String destinationHint, Instant createdAt) {
}
