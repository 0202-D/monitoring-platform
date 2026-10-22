package com.monitoring.coreapi.repository;

import com.monitoring.coreapi.entity.Monitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {
    List<Monitor> findByUserId(UUID userId);
    // Ownership-check встроен прямо в запрос: если монитор существует,
    // но принадлежит другому пользователю, возвращается empty — сервис
    // не узнаёт разницу между "не найдено" и "чужое", что и правильно
    // с точки зрения безопасности (не раскрываем факт существования
    // чужих ресурсов по id).
    Optional<Monitor> findByIdAndUserId(UUID id, UUID userId);
}
