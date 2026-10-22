package com.monitoring.webui.client.dto;

public record CreateMonitorRequestDto(
        String type,
        String targetUrl,
        int checkIntervalSeconds
) {
}

