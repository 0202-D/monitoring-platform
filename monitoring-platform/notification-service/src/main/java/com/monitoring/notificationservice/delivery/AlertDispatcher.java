package com.monitoring.notificationservice.delivery;

import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.telegram.TelegramApiClient;
import com.monitoring.notificationservice.telegram.TelegramApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Доставка события пользователю по его каналам. Сейчас канал один — Telegram.
 *
 * Идемпотентность. Kafka даёт at-least-once, поэтому одно событие может прийти
 * повторно (перебалансировка, перезапуск, сброс offset). Порядок действий:
 * проверить processed_event -> отправить -> записать processed_event.
 * Дубль сообщения возможен только если процесс упадёт между отправкой и
 * записью. Записать ДО отправки нельзя: при сбое отправки алерт был бы
 * потерян навсегда, а потерянный алерт хуже повторного.
 */
@Service
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private static final String TELEGRAM = "TELEGRAM";

    private final UserChannelRepository channelRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final AlertMessageFormatter formatter;
    private final TelegramApiClient telegramApi;

    public AlertDispatcher(UserChannelRepository channelRepository,
                           ProcessedEventRepository processedEventRepository,
                           AlertMessageFormatter formatter,
                           TelegramApiClient telegramApi) {
        this.channelRepository = channelRepository;
        this.processedEventRepository = processedEventRepository;
        this.formatter = formatter;
        this.telegramApi = telegramApi;
    }

    public void dispatch(MonitorStatusEvent event) {
        UUID userId;
        try {
            userId = UUID.fromString(event.getUserId());
        } catch (IllegalArgumentException e) {
            // Повторять бессмысленно: событие с таким userId не станет
            // корректным. Пропускаем, а не бросаем исключение.
            log.warn("Событие {} с некорректным userId '{}' пропущено", event.getEventId(), event.getUserId());
            return;
        }

        Optional<String> chatId = channelRepository.findActiveDestination(userId, TELEGRAM);
        if (chatId.isEmpty()) {
            log.debug("У пользователя {} нет активного Telegram-канала, событие {} не доставляется",
                userId, event.getEventId());
            return;
        }

        if (!telegramApi.isConfigured()) {
            log.warn("TELEGRAM_BOT_TOKEN не задан: уведомление по событию {} не отправлено", event.getEventId());
            return;
        }

        if (processedEventRepository.exists(event.getEventId(), TELEGRAM)) {
            log.info("Событие {} уже доставлено в Telegram, повтор пропущен", event.getEventId());
            return;
        }

        try {
            telegramApi.sendMessage(Long.parseLong(chatId.get()), formatter.format(event));
        } catch (TelegramApiException e) {
            if (e.isPermanent()) {
                log.warn("Telegram-канал пользователя {} отключён: {}", userId, e.getMessage());
                channelRepository.deactivate(userId, TELEGRAM);
                return;
            }
            // Временный сбой: пробрасываем, DefaultErrorHandler повторит доставку
            throw e;
        }

        processedEventRepository.markProcessed(event.getEventId(), TELEGRAM);
        log.info("Событие {} [{}] доставлено пользователю {} в Telegram",
            event.getEventId(), event.getEventType(), userId);
    }
}
