package com.monitoring.notificationservice.consumer;

import com.monitoring.events.MonitorStatusEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Пока только логирует события — это каркас для проверки цепочки
 * checker-worker → outbox → Kafka → notification-service. Реальная
 * доставка в Telegram и идемпотентность (таблица processed_event)
 * добавятся следующими этапами.
 *
 * Режим подтверждения по умолчанию (offset коммитится после успешного
 * возврата из метода) даёт at-least-once: если метод бросит исключение,
 * сообщение будет доставлено повторно. По умолчанию Spring Kafka делает
 * несколько повторов подряд и затем пропускает сообщение с записью в лог;
 * dead-letter топик настроим, когда появится реальная отправка.
 */
@Component
@Slf4j
public class MonitorStatusEventConsumer {

    @KafkaListener(topics = "${notification.topic}")
    public void onEvent(MonitorStatusEvent event) {
        log.info("Получено событие {} [{}]: монитор {} ({}), пользователь {}, детали: {}",
            event.getEventId(), event.getEventType(), event.getMonitorId(),
            event.getTargetUrl(), event.getUserId(), event.getDetails());
    }
}
