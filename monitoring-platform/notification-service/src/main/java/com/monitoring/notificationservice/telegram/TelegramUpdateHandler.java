package com.monitoring.notificationservice.telegram;

import com.monitoring.notificationservice.channel.TelegramLinkService;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.telegram.TelegramModels.Message;
import com.monitoring.notificationservice.telegram.TelegramModels.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Логика бота. Сейчас бот понимает одну команду: /start с токеном привязки.
 */
@Component
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    private static final String HELP_TEXT =
        "Привет! Я присылаю уведомления о состоянии ваших мониторов. "
            + "Чтобы подключить Telegram, откройте приложение и нажмите «Подключить Telegram».";

    private static final String LINKED_TEXT =
        "Готово! Telegram подключён, сюда будут приходить уведомления о смене статуса мониторов.";

    private static final String INVALID_TOKEN_TEXT =
        "Ссылка недействительна или устарела. Создайте новую ссылку в приложении.";

    private final TelegramApiClient api;
    private final TelegramLinkService linkService;
    private final UserChannelRepository channelRepository;

    public TelegramUpdateHandler(TelegramApiClient api,
                                 TelegramLinkService linkService,
                                 UserChannelRepository channelRepository) {
        this.api = api;
        this.linkService = linkService;
        this.channelRepository = channelRepository;
    }

    public void handle(Update update) {
        Message message = update.message();
        if (message == null || message.text() == null || message.chat() == null) {
            return;
        }
        // Привязываем только личные диалоги. Если бота добавят в группу,
        // чат группы не должен стать каналом уведомлений пользователя.
        if (!"private".equals(message.chat().type())) {
            return;
        }

        long chatId = message.chat().id();
        String[] parts = message.text().trim().split("\\s+", 2);
        String command = parts[0];
        if (!command.equals("/start") && !command.startsWith("/start@")) {
            return;
        }

        if (parts.length < 2 || parts[1].isBlank()) {
            api.sendMessage(chatId, HELP_TEXT);
            return;
        }

        Optional<UUID> userId = linkService.consumeToken(parts[1].trim());
        if (userId.isEmpty()) {
            api.sendMessage(chatId, INVALID_TOKEN_TEXT);
            return;
        }

        channelRepository.upsert(userId.get(), "TELEGRAM", String.valueOf(chatId));
        log.info("Telegram привязан для пользователя {}", userId.get());
        api.sendMessage(chatId, LINKED_TEXT);
    }
}
