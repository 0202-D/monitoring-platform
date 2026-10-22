package com.monitoring.checkerworker.repository;

import java.util.UUID;

/**
 * Снимок данных монитора на момент "захвата" планировщиком — не JPA entity,
 * просто неизменяемая проекция результата SELECT. Immutable намеренно:
 * между захватом и фактической проверкой (которая выполняется асинхронно
 * на virtual thread) этот объект путешествует между потоками, и его
 * неизменяемость исключает целый класс конкурентных багов.
 */
public record DueMonitor(
        UUID id,
        UUID userId,            // app_user.id — внутренний идентификатор core-api
        String userSubject,     // Keycloak sub — общий для ВСЕХ сервисов идентификатор пользователя
        String type,            // HTTP, SSL, DNS
        String targetUrl,
        int checkIntervalSeconds,
        String currentStatus,   // UNKNOWN, UP, DOWN — статус ДО этой проверки
        int consecutiveFailures
) {
}