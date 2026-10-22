package com.monitoring.coreapi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "monitor")
@Getter
@Setter
@NoArgsConstructor
public class Monitor {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MonitorType type;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    @Column(name = "check_interval_seconds", nullable = false)
    private int checkIntervalSeconds = 300;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false)
    private Status currentStatus = Status.UNKNOWN;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "next_check_at", nullable = false)
    private OffsetDateTime nextCheckAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Monitor(UUID id, UUID userId, MonitorType type, String targetUrl, int checkIntervalSeconds) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.targetUrl = targetUrl;
        this.checkIntervalSeconds = checkIntervalSeconds;
        // Первая проверка планируется сразу же, не через checkIntervalSeconds —
        // пользователь ожидает увидеть первый результат сразу после создания монитора
        this.nextCheckAt = OffsetDateTime.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public enum MonitorType {
        HTTP, SSL, DNS
    }

    public enum Status {
        UNKNOWN, UP, DOWN
    }
}
