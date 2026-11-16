package com.monitoring.notificationservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * Повторы при временных сбоях доставки (Telegram недоступен, сеть):
     * паузы 1, 2, 4, 8, 16, 30 секунд — около минуты в сумме. Если и после
     * этого доставка не удалась, сообщение пропускается, а алерт теряется:
     * это считается в метрике alerts.dropped и пишется в лог с уровнем ERROR.
     * Spring Boot сам подключает этот бин к автоконфигурированной фабрике слушателей.
     *
     * Известные ограничения: пока идут повторы, партиция стоит, а в неё
     * попадают и события других мониторов; потерянный алерт никто не
     * переотправляет. Решение — dead-letter топик, это отдельный шаг.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(MeterRegistry meterRegistry) {
        // Регистрируем сразу, с нулём: иначе ряд появится только после первой
        // потери, и первый случай будет не виден в графиках
        Counter dropped = Counter.builder("alerts.dropped")
            .description("Алерты, потерянные после исчерпания повторов доставки")
            .register(meterRegistry);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);

        // Вызывается, когда повторы исчерпаны
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            dropped.increment();
            log.error("Алерт потерян после исчерпания повторов: topic={}, partition={}, offset={}, причина: {}",
                record.topic(), record.partition(), record.offset(), exception.getMessage());
        };

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
