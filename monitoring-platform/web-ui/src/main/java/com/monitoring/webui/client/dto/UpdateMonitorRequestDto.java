package com.monitoring.webui.client.dto;

public record UpdateMonitorRequestDto(
    String targetUrl,
    int checkIntervalSeconds,
    boolean active
) {
}
