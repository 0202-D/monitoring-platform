package com.monitoring.notificationservice.telegram;

/**
 * Ошибка обращения к Telegram Bot API. Сообщение уже очищено от токена бота.
 * Исходное исключение намеренно НЕ присоединяется как cause: сообщение
 * ResourceAccessException содержит полный URL запроса, а токен бота
 * является частью этого URL — стектрейс в логе раскрыл бы его.
 */
public class TelegramApiException extends RuntimeException {

    public TelegramApiException(String message) {
        super(message);
    }
}
