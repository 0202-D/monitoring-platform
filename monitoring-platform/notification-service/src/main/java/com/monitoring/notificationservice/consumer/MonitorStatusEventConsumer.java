package com.monitoring.notificationservice.consumer;

import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.delivery.AlertDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Режим подтверждения по умолчанию: offset коммитится после успешного
 * возврата из метода (at-least-once). Если метод бросает исключение,
 * срабатывает DefaultErrorHandler из KafkaConsumerConfig: повторы с
 * нарастающей паузой, затем сообщение пропускается с записью в лог.
 */
@Component
@Slf4j
public class MonitorStatusEventConsumer {

    private final AlertDispatcher alertDispatcher;

    public MonitorStatusEventConsumer(AlertDispatcher alertDispatcher) {
        this.alertDispatcher = alertDispatcher;
    }

    @KafkaListener(topics = "${notification.topic}")
    public void onEvent(MonitorStatusEvent event) {
        log.info("Получено событие {} [{}]: монитор {}, пользователь {}",
                event.getEventId(), event.getEventType(), event.getMonitorId(), event.getUserId());
        alertDispatcher.dispatch(event);
    }
}
