package com.monitoring.notificationservice.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Минимальный набор полей Telegram Bot API, который нам нужен.
 * Всё остальное в ответах Telegram игнорируется.
 */
public final class TelegramModels {

    private TelegramModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiResponse<T>(boolean ok, T result, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(@JsonProperty("update_id") long updateId, Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Chat chat, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type) {
    }
}
