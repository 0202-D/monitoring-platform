package com.monitoring.checkerworker.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String MONITOR_STATUS_EVENTS_TOPIC = "monitor-status-events";

    /**
     * KafkaAdmin (автоконфигурируется Spring Boot) при старте приложения
     * создаст топик, если его ещё нет. Это удобно для dev-окружения;
     * в проде топики обычно создаются отдельно (IaC / админ-скрипты) с
     * осознанно выбранными partitions/replication/retention.
     *
     * 3 партиции: сообщения одного монитора (ключ = monitorId) всегда
     * попадают в одну партицию, поэтому порядок событий DOWN → UP для
     * конкретного монитора сохраняется, а разные мониторы могут
     * обрабатываться консьюмерами параллельно.
     *
     * replicas(1) — потому что в docker-compose у нас один брокер.
     */
    @Bean
    public NewTopic monitorStatusEventsTopic() {
        return TopicBuilder.name(MONITOR_STATUS_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
