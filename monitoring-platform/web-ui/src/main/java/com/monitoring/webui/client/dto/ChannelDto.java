package com.monitoring.webui.client.dto;

import java.time.Instant;

/** Зеркалирует UserChannelRepository.ChannelInfo из notification-service. */
public record ChannelDto(String channelType, boolean active, Instant createdAt) {
}
