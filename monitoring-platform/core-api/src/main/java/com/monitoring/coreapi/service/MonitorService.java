package com.monitoring.coreapi.service;

import com.monitoring.coreapi.entity.AppUser;
import com.monitoring.coreapi.entity.Monitor;
import com.monitoring.coreapi.repository.MonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MonitorService {
    private final JdbcTemplate jdbcTemplate;

    // На старте — просто константа. Когда появятся разные тарифы с разными
    // лимитами, вынесем в конфигурацию/таблицу планов, но городить это заранее
    // ради одной FREE-цифры смысла нет.
    private static final int FREE_PLAN_MONITOR_LIMIT = 5;

    private final MonitorRepository monitorRepository;

    @Transactional
    public Monitor createMonitor(AppUser owner, Monitor.MonitorType type, String targetUrl, int intervalSeconds) {
        if (owner.getPlan() == AppUser.Plan.FREE) {
            long existing = monitorRepository.findByUserId(owner.getId()).size();
            if (existing >= FREE_PLAN_MONITOR_LIMIT) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Достигнут лимит FREE-плана (%d мониторов). Оформите PRO для увеличения лимита."
                        .formatted(FREE_PLAN_MONITOR_LIMIT));
            }
        }

        Monitor monitor = new Monitor(UUID.randomUUID(), owner.getId(), type, targetUrl, intervalSeconds);
        return monitorRepository.save(monitor);
    }

    @Transactional
    public Monitor updateMonitor(UUID monitorId, UUID requesterId, String targetUrl,
                                 int checkIntervalSeconds, boolean active) {
        Monitor monitor = findOwnedOrThrow(monitorId, requesterId);

        monitor.setTargetUrl(targetUrl);
        monitor.setCheckIntervalSeconds(checkIntervalSeconds);
        // next_check_at сознательно не трогаем при обычном редактировании —
        // следующая проверка по-прежнему придёт в ранее запланированное
        // время с учётом нового интервала. Единственное исключение —
        // реактивация после паузы (false → true): тогда next_check_at
        // мог "протухнуть" далеко в прошлом, пока active был false
        // (checker-worker не захватывает неактивные мониторы вообще,
        // так что next_check_at просто не продвигался всё это время) —
        // без явного сброса первая проверка после реактивации случится
        // немедленно на следующем же цикле опроса, что на самом деле
        // нормальное и ожидаемое поведение, а не баг — трогать не нужно.
        monitor.setActive(active);

        return monitorRepository.save(monitor);
    }

    @Transactional
    public void deleteMonitor(UUID monitorId, UUID requesterId) {
        Monitor monitor = findOwnedOrThrow(monitorId, requesterId);

        // Сначала дочерние check_result (FK REFERENCES monitor(id) без
        // ON DELETE CASCADE в миграции 003 — без этой явной очистки
        // DELETE монитора упал бы на foreign key constraint violation).
        // outbox_event НЕ трогаем: у него нет FK на monitor (намеренно,
        // см. комментарий в 004-create-outbox-event.yaml) — события
        // должны пережить удаление монитора для аудита/истории уведомлений.
        jdbcTemplate.update("DELETE FROM check_result WHERE monitor_id = ?", monitorId);
        monitorRepository.delete(monitor);
    }

    private Monitor findOwnedOrThrow(UUID monitorId, UUID requesterId) {
        return monitorRepository.findByIdAndUserId(monitorId, requesterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Монитор не найден"));
    }

    public List<Monitor> listMonitorsForUser(UUID userId) {
        return monitorRepository.findByUserId(userId);
    }
}
