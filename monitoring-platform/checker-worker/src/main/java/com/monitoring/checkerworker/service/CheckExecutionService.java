package com.monitoring.checkerworker.service;

import com.monitoring.checkerworker.checks.CheckResult;
import com.monitoring.checkerworker.checks.CheckerRegistry;
import com.monitoring.checkerworker.repository.DueMonitor;
import com.monitoring.checkerworker.repository.MonitorJdbcRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executors.newVirtualThreadPerTaskExecutor() — по одному виртуальному
 * потоку на КАЖДУЮ проверку, без ограничения размера пула. Это оправдано
 * именно для I/O-bound нагрузки (сетевые проверки, которые почти всё время
 * проводят в ожидании ответа, не нагружая CPU): виртуальные потоки дёшевы
 * (килобайты, не мегабайты памяти на стек), и JVM сама мультиплексирует
 * их поверх небольшого числа platform-потоков во время реального ожидания
 * I/O. Для сравнения — пул из, скажем, 200 platform-потоков был бы и
 * избыточно тяжёл по памяти, и всё равно ограничивал бы параллелизм
 * искусственным числом, подобранным наугад.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckExecutionService {

    private final CheckerRegistry checkerRegistry;
    private final MonitorJdbcRepository repository;

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void executeChecks(List<DueMonitor> monitors) {
        for (DueMonitor monitor : monitors) {
            virtualThreadExecutor.submit(() -> executeOne(monitor));
        }
    }

    private void executeOne(DueMonitor monitor) {
        var checker = checkerRegistry.find(monitor.type());
        if (checker.isEmpty()) {
            // DNS и любой другой пока не реализованный тип — явно
            // пропускаем с предупреждением, а не притворяемся, что
            // проверили. next_check_at уже сдвинут в claimDueMonitors,
            // так что это не бесконечный busy-loop — повторная попытка
            // будет через обычный интервал монитора.
            log.warn("Нет реализации Checker для типа '{}' (monitor {}), пропускаю",
                monitor.type(), monitor.id());
            return;
        }

        try {
            CheckResult result = checker.get().check(monitor.targetUrl());
            repository.recordCheckOutcome(monitor, result);
        } catch (Exception e) {
            // Любое необработанное исключение здесь (например, сбой самой
            // записи в БД — отдельно от сетевых ошибок, которые Checker
            // уже перехватывает и оборачивает в CheckResult.down(...))
            // логируется и ИЗОЛИРОВАННО для этого монитора: виртуальный
            // поток одной проверки падает, не затрагивая остальные
            // параллельно выполняющиеся проверки и не роняя весь воркер.
            log.error("Необработанная ошибка при проверке монитора {}", monitor.id(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        virtualThreadExecutor.shutdown();
    }
}
