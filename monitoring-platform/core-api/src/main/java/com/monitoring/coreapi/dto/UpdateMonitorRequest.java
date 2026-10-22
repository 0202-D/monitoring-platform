package com.monitoring.coreapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpdateMonitorRequest(
        @NotBlank
        String targetUrl,

        @Min(value = 60, message = "Минимальный интервал проверки — 60 секунд")
        int checkIntervalSeconds,

        boolean active
) {
}
