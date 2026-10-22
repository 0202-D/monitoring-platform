package com.monitoring.checkerworker.service;

import com.monitoring.checkerworker.repository.DueMonitor;
import com.monitoring.checkerworker.repository.MonitorJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonitorSchedulerService {

    private final MonitorJdbcRepository repository;
    private final CheckExecutionService checkExecutionService;

    @Value("${checker.batch-size:50}")
    private int batchSize;

    /**
     * fixedDelay (не fixedRate): следующий запуск отсчитывается от
     * ЗАВЕРШЕНИЯ предыдущего, а не от его начала. claimDueMonitors — сама
     * по себе быстрая транзакция (просто SELECT+UPDATE нескольких строк),
     * так что разница с fixedRate здесь почти не ощутима на практике, но
     * fixedDelay — более безопасный выбор по умолчанию: если по какой-то
     * причине метод вдруг выполнится медленно (например, БД временно
     * недоступна и JDBC-вызов зависнет на таймауте), fixedRate начал бы
     * накапливать параллельные запуски друг на друге, а fixedDelay — нет.
     */
    @Scheduled(fixedDelayString = "${checker.poll-interval-ms:5000}")
    public void pollAndDispatch() {
        List<DueMonitor> due = repository.claimDueMonitors(batchSize);
        if (due.isEmpty()) {
            return;
        }
        log.info("Dispatching {} monitor checks", due.size());
        checkExecutionService.executeChecks(due);
        // Намеренно НЕ ждём завершения проверок здесь — executeChecks
        // отправляет задачи на virtual threads и сразу возвращает
        // управление. Это позволяет планировщику вернуться к опросу БД
        // не дожидаясь самых медленных/таймаутящих проверок из текущего
        // батча — медленный HTTP-запрос к одному сайту не должен
        // задерживать захват следующей партии мониторов.
    }
}
