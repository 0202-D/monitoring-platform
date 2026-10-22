package com.monitoring.coreapi.dto;

import com.monitoring.coreapi.entity.Monitor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Это то, что видит клиент API — не JPA entity напрямую.
 * Намеренно не отдаём наружу consecutiveFailures (внутренняя техническая деталь
 * логики алертинга) и userId (клиент и так авторизован под своим токеном,
 * дублировать его собственный id в каждом мониторе незачем).
 */
public record MonitorResponse(
    UUID id,
    String type,
    String targetUrl,
    int checkIntervalSeconds,
    boolean active,
    String currentStatus,
    OffsetDateTime nextCheckAt,
    OffsetDateTime createdAt
) {
    public static MonitorResponse from(Monitor monitor) {
        return new MonitorResponse(
            monitor.getId(),
            monitor.getType().name(),
            monitor.getTargetUrl(),
            monitor.getCheckIntervalSeconds(),
            monitor.isActive(),
            monitor.getCurrentStatus().name(),
            monitor.getNextCheckAt(),
            monitor.getCreatedAt()
        );
    }
}
