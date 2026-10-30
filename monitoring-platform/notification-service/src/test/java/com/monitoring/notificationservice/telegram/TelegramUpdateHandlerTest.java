package com.monitoring.notificationservice.telegram;

import com.monitoring.notificationservice.channel.TelegramLinkService;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.telegram.TelegramModels.Chat;
import com.monitoring.notificationservice.telegram.TelegramModels.Message;
import com.monitoring.notificationservice.telegram.TelegramModels.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramUpdateHandlerTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final long CHAT_ID = 555L;

    @Mock
    private TelegramApiClient api;
    @Mock
    private TelegramLinkService linkService;
    @Mock
    private UserChannelRepository channelRepository;

    private TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramUpdateHandler(api, linkService, channelRepository);
    }

    @Test
    void linksChatWhenTokenIsValid() {
        when(linkService.consumeToken("tok")).thenReturn(Optional.of(USER_ID));

        handler.handle(update("private", "/start tok"));

        // chat_id берётся из сообщения Telegram, а не от клиента: подделать его нельзя
        verify(channelRepository).upsert(USER_ID, "TELEGRAM", "555");
        verify(api).sendMessage(eq(CHAT_ID), contains("Готово"));
    }

    @Test
    void rejectsInvalidOrExpiredToken() {
        when(linkService.consumeToken("bad")).thenReturn(Optional.empty());

        handler.handle(update("private", "/start bad"));

        verify(channelRepository, never()).upsert(any(UUID.class), anyString(), anyString());
        verify(api).sendMessage(eq(CHAT_ID), contains("недействительна"));
    }

    @Test
    void acceptsCommandWithBotUsernameSuffix() {
        when(linkService.consumeToken("tok")).thenReturn(Optional.of(USER_ID));

        handler.handle(update("private", "/start@petrov_monitoring_bot tok"));

        verify(channelRepository).upsert(USER_ID, "TELEGRAM", "555");
    }

    @Test
    void answersWithHelpWhenStartHasNoToken() {
        handler.handle(update("private", "/start"));

        verify(api).sendMessage(eq(CHAT_ID), contains("Привет"));
        verifyNoInteractions(linkService, channelRepository);
    }

    @Test
    void ignoresGroupChats() {
        // Чат группы не должен стать каналом уведомлений пользователя
        handler.handle(update("group", "/start tok"));

        verifyNoInteractions(api, linkService, channelRepository);
    }

    @Test
    void ignoresOrdinaryMessages() {
        handler.handle(update("private", "hello"));

        verifyNoInteractions(api, linkService, channelRepository);
    }

    @Test
    void ignoresUpdatesWithoutMessage() {
        handler.handle(new Update(1L, null));

        verifyNoInteractions(api, linkService, channelRepository);
    }

    private Update update(String chatType, String text) {
        return new Update(1L, new Message(new Chat(CHAT_ID, chatType), text));
    }
}
