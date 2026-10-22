package com.monitoring.coreapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMonitorRequest(

    @NotNull
    MonitorTypeDto type,

    @NotBlank
    String targetUrl,

    @Min(value = 60, message = "Минимальный интервал проверки — 60 секунд, " +
        "чтобы не превращать сервис в инструмент для DDoS чужих сайтов")
    int checkIntervalSeconds
) {
    public enum MonitorTypeDto {
        HTTP, SSL, DNS
    }
}
