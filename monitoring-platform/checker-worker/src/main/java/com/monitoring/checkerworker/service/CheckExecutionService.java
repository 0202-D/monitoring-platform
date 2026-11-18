package com.monitoring.checkerworker.service;

import com.monitoring.checkerworker.checks.CheckResult;
import com.monitoring.checkerworker.checks.Checker;
import com.monitoring.checkerworker.checks.CheckerRegistry;
import com.monitoring.checkerworker.repository.DueMonitor;
import com.monitoring.checkerworker.repository.MonitorJdbcRepository;
import com.monitoring.checkerworker.tracing.TraceContextCarrier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executors.newVirtualThreadPerTaskExecutor() — по одному виртуальному
 * потоку на КАЖДУЮ проверку, без ограничения размера пула. Это оправдано
 * именно для I/O-bound нагрузки (сетевые проверки, которые почти всё время
 * проводят в ожидании ответа, не нагружая CPU): виртуальные потоки дёшевы
 * (килобайты, не мегабайты памяти на стек), и JVM сама мультиплексирует
 * их поверх небольшого числа platform-потоков во время реального ожидания I/O.
 * Трассировка: пул виртуальных потоков не передаёт контекст трассировки между
 * потоками, поэтому span создаётся ВНУТРИ потока проверки. Каждая проверка —
 * отдельный корневой span "monitor.check".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckExecutionService {

    private final CheckerRegistry checkerRegistry;
    private final MonitorJdbcRepository repository;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final TraceContextCarrier traceContextCarrier;

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
            // так что это не бесконечный busy-loop.
            log.warn("Нет реализации Checker для типа '{}' (monitor {}), пропускаю",
                monitor.type(), monitor.id());
            return;
        }

        // monitor.id — высококардинальный тег: попадает в span (по нему трассу
        // можно найти в Zipkin), но НЕ в метрики, где такой тег раздул бы число рядов
        Observation observation = Observation.createNotStarted("monitor.check", observationRegistry)
            .lowCardinalityKeyValue("monitor.type", monitor.type())
            .highCardinalityKeyValue("monitor.id", monitor.id().toString());

        try {
            observation.observe(() -> runCheck(monitor, checker.get()));
        } catch (Exception e) {
            // Любое необработанное исключение (например, сбой записи в БД — отдельно
            // от сетевых ошибок, которые Checker уже оборачивает в CheckResult.down)
            // изолировано для этого монитора: виртуальный поток одной проверки
            // падает, не затрагивая остальные проверки и не роняя весь воркер.
            // Observation при этом уже пометила span как ошибочный.
            log.error("Необработанная ошибка при проверке монитора {}", monitor.id(), e);
        }
    }

    private void runCheck(DueMonitor monitor, Checker checker) {
        Timer.Sample sample = Timer.start(meterRegistry);
        CheckResult result = checker.check(monitor.targetUrl());
        sample.stop(Timer.builder("monitor.check.duration")
            .description("Длительность выполнения проверки монитора")
            .tag("type", monitor.type())
            .publishPercentileHistogram()
            .register(meterRegistry));
        meterRegistry.counter("monitor.checks",
            "type", monitor.type(),
            "result", result.status().name().toLowerCase(Locale.ROOT)).increment();
        // Мы внутри наблюдения "monitor.check": сохраняем его контекст вместе с
        // событием, чтобы публикация в Kafka продолжила эту же трассу
        repository.recordCheckOutcome(monitor, result, traceContextCarrier.captureCurrent());
    }

    @PreDestroy
    public void shutdown() {
        virtualThreadExecutor.shutdown();
    }
}
