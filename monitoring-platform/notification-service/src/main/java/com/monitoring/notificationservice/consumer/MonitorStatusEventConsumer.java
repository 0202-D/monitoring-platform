package com.monitoring.notificationservice.consumer;

import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.delivery.DeliveryPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Консьюмер только ставит событие в очередь доставки (быстрая запись в БД) и сразу
 * подтверждает сообщение. Отправка выполняется отдельно (DeliveryWorker), поэтому сбой
 * внешнего канала не блокирует партицию Kafka.
 *
 * Если сама запись в БД не удалась, исключение уходит в DefaultErrorHandler:
 * повторы с нарастающей паузой (см. KafkaConsumerConfig).
 */
@Component
@Slf4j
public class MonitorStatusEventConsumer {

    private final DeliveryPlanner deliveryPlanner;

    public MonitorStatusEventConsumer(DeliveryPlanner deliveryPlanner) {
        this.deliveryPlanner = deliveryPlanner;
    }

    @KafkaListener(topics = "${notification.topic}")
    public void onEvent(MonitorStatusEvent event) {
        log.info("Получено событие {} [{}]: монитор {}, пользователь {}",
            event.getEventId(), event.getEventType(), event.getMonitorId(), event.getUserId());
        deliveryPlanner.plan(event);
    }
}
