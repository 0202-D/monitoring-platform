package com.monitoring.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConsumerConfig {

    /**
     * Повторы при временных сбоях доставки (Telegram недоступен, сеть):
     * паузы 1, 2, 4, 8, 16, 30 секунд — около минуты в сумме, затем сообщение
     * пропускается с записью ERROR в лог. Spring Boot сам подключает этот бин
     * к автоконфигурированной фабрике слушателей.
     *
     * Известные ограничения: пока идут повторы, партиция стоит, а в неё
     * попадают и события других мониторов; и после исчерпания повторов
     * алерт теряется. Решение — dead-letter топик, это отдельный шаг.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        return new DefaultErrorHandler(backOff);
    }
}
