package com.monitoring.notificationservice.telegram;

/**
 * Ошибка обращения к Telegram Bot API. Сообщение уже очищено от токена бота.
 * Исходное исключение намеренно НЕ присоединяется как cause: сообщение
 * ResourceAccessException содержит полный URL запроса, а токен бота
 * является частью этого URL — стектрейс в логе раскрыл бы его.
 *
 * permanent = повторять бессмысленно (бот заблокирован пользователем,
 * чат не существует). Временные сбои (сеть, 5xx, 429) permanent не помечаются.
 */
public class TelegramApiException extends RuntimeException {

    private final boolean permanent;

    public TelegramApiException(String message) {
        this(message, false);
    }

    public TelegramApiException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}

