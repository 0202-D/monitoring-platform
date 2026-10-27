package com.monitoring.coreapi.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    private UUID id;

    // sub claim из JWT Keycloak. По этому полю ищем пользователя при первом
    // запросе с валидным токеном — если не нашли, создаём (JIT provisioning),
    // см. AppUserService.
    @Column(name = "keycloak_subject", nullable = false, unique = true)
    private String keycloakSubject;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public AppUser(UUID id, String keycloakSubject, String email) {
        this.id = id;
        this.keycloakSubject = keycloakSubject;
        this.email = email;
        this.plan = Plan.FREE;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public enum Plan {
        FREE, PRO
    }
}
