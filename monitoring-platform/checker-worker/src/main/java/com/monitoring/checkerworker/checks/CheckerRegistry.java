package com.monitoring.checkerworker.checks;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class CheckerRegistry {

    // find() вызывается параллельно из множества virtual threads
    // (CheckExecutionService проверяет мониторы конкурентно) — карта
    // собирается один раз в конструкторе и больше не меняется, что
    // автоматически делает чтение из неё безопасным без явной
    // синхронизации (publication через final-поле конструктора).
    private final Map<String, Checker> byType;

    public CheckerRegistry(HttpChecker httpChecker, SslChecker sslChecker) {
        this.byType = Map.of(
            "HTTP", httpChecker,
            "SSL", sslChecker
            // DNS сознательно не включён: WHOIS-проверка домена требует
            // отдельного протокола/библиотеки и вынесена в отдельную
            // задачу на будущее (см. CheckExecutionService — мониторы
            // типа DNS сейчас пропускаются с явным предупреждением
            // в логе, а не падают и не притворяются, что работают).
        );
    }

    public Optional<Checker> find(String monitorType) {
        return Optional.ofNullable(byType.get(monitorType));
    }
}
