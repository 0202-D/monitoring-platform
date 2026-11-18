package com.monitoring.checkerworker.tracing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Сохранение и восстановление контекста трассировки через базу данных.
 *
 * Паттерн outbox намеренно разрывает цепочку: событие пишется в БД в момент
 * проверки, а в Kafka уходит позже отдельным потоком. Чтобы трасса не
 * оборвалась, текущий контекст сохраняется в строке outbox_event и
 * восстанавливается при публикации.
 *
 * Формат хранения — JSON-карта заголовков распространения (какие именно
 * заголовки, определяет Propagator: у нас это W3C traceparent), поэтому код
 * не зависит от выбранного формата.
 */
@Component
public class TraceContextCarrier {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final Tracer tracer;
    private final Propagator propagator;
    // Не Spring-бин, по той же причине, что и в MonitorJdbcRepository:
    // автоконфигурация ObjectMapper здесь недоступна
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TraceContextCarrier(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** JSON с заголовками текущего span'а или null, если трассировки сейчас нет. */
    public String captureCurrent() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        if (carrier.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(carrier);
        } catch (JsonProcessingException e) {
            // Трассировка вторична: её сбой не должен ломать запись события
            return null;
        }
    }

    /** Разбор сохранённого контекста. Пустая карта означает "родителя нет". */
    public Map<String, String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(stored, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
