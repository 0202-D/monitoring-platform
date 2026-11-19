package com.monitoring.notificationservice.tracing;

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
 * Доставка выполняется воркером позже и в другом потоке, чем приём события из Kafka,
 * поэтому контекст приёма сохраняется в строке очереди, и воркер продолжает ту же
 * трассу. Тот же приём, что и в checker-worker для outbox.
 * (Класс продублирован осознанно: общий модуль ради одного класса избыточен.)
 */
@Component
public class TraceContextCarrier {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final Tracer tracer;
    private final Propagator propagator;
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
