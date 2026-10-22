package com.monitoring.webui.client.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Зеркалирует com.monitoring.coreapi.dto.MonitorResponse. Дублирование
 * временное: когда на следующих шагах опишем OpenAPI-контракт core-api
 * contract-first (как договаривались изначально), эти классы можно будет
 * генерировать через openapi-generator-maven-plugin вместо ручного
 * поддержания синхронности с сервером руками.
 */
public record MonitorDto(
        UUID id,
        String type,
        String targetUrl,
        int checkIntervalSeconds,
        boolean active,
        String currentStatus,
        OffsetDateTime nextCheckAt,
        OffsetDateTime createdAt
) {
}
