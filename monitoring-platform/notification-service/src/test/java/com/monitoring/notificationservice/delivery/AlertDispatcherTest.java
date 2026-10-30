package com.monitoring.notificationservice.delivery;

import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.telegram.TelegramApiClient;
import com.monitoring.notificationservice.telegram.TelegramApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertDispatcherTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final String TELEGRAM = "TELEGRAM";

    @Mock
    private UserChannelRepository channelRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private TelegramApiClient telegramApi;

    private AlertDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new AlertDispatcher(
            channelRepository, processedEventRepository, new AlertMessageFormatter(), telegramApi);
    }

    @Test
    void sendsMessageAndMarksEventProcessed() {
        when(channelRepository.findActiveDestination(USER_ID, TELEGRAM)).thenReturn(Optional.of("12345"));
        when(telegramApi.isConfigured()).thenReturn(true);
        when(processedEventRepository.exists("event-1", TELEGRAM)).thenReturn(false);

        dispatcher.dispatch(event(USER_ID.toString()));

        verify(telegramApi).sendMessage(eq(12345L), contains("Монитор недоступен"));
        verify(processedEventRepository).markProcessed("event-1", TELEGRAM);
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        // Повторная доставка из Kafka не должна давать второе сообщение
        when(channelRepository.findActiveDestination(USER_ID, TELEGRAM)).thenReturn(Optional.of("12345"));
        when(telegramApi.isConfigured()).thenReturn(true);
        when(processedEventRepository.exists("event-1", TELEGRAM)).thenReturn(true);

        dispatcher.dispatch(event(USER_ID.toString()));

        verify(telegramApi, never()).sendMessage(anyLong(), anyString());
        verify(processedEventRepository, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void doesNothingWhenUserHasNoActiveChannel() {
        when(channelRepository.findActiveDestination(USER_ID, TELEGRAM)).thenReturn(Optional.empty());

        dispatcher.dispatch(event(USER_ID.toString()));

        verifyNoInteractions(telegramApi, processedEventRepository);
    }

    @Test
    void skipsEventWithInvalidUserIdWithoutThrowing() {
        // Повторять бессмысленно: исключение привело бы к бесполезным повторам доставки
        dispatcher.dispatch(event("not-a-uuid"));

        verifyNoInteractions(channelRepository, telegramApi, processedEventRepository);
    }

    @Test
    void doesNotSendWhenBotTokenIsNotConfigured() {
        when(channelRepository.findActiveDestination(USER_ID, TELEGRAM)).thenReturn(Optional.of("12345"));
        when(telegramApi.isConfigured()).thenReturn(false);

        dispatcher.dispatch(event(USER_ID.toString()));

        verify(telegramApi, never()).sendMessage(anyLong(), anyString());
        verifyNoInteractions(processedEventRepository);
    }

    @Test
    void deactivatesChannelOnPermanentTelegramFailure() {
        // Бот заблокирован пользователем: повторы бессмысленны, канал отключается
        when(channelRepository.findActiveDestination(USER_ID, TELEGRAM)).thenReturn(Optional.of("12345"));
        when(telegramApi.isConfigured()).thenReturn(true);
        when(processedEventRepository.exists("event-1", TELEGRAM)).thenReturn(false);
        doThrow(new TelegramApiException("HTTP 403", true)).when(telegramApi).sendMessage(anyLong(), anyString());

        dispatcher.dispatch(event(USER_ID.toString()));

        verify(channelRepository).deactivate(USER_ID, TELEGRAM);
        verify(processedEventRepository, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void rethrowsTransientTelegramFailureSoKafkaRetries() {
        when(channelRepository.findActiveDestination(USER_ID, TELEGRAM)).thenReturn(Optional.of("12345"));
        when(telegramApi.isConfigured()).thenReturn(true);
        when(processedEventRepository.exists("event-1", TELEGRAM)).thenReturn(false);
        doThrow(new TelegramApiException("timeout")).when(telegramApi).sendMessage(anyLong(), anyString());

        assertThatThrownBy(() -> dispatcher.dispatch(event(USER_ID.toString())))
            .isInstanceOf(TelegramApiException.class);

        // Канал не отключается и событие не помечается доставленным
        verify(channelRepository, never()).deactivate(any(UUID.class), anyString());
        verify(processedEventRepository, never()).markProcessed(anyString(), anyString());
    }

    private MonitorStatusEvent event(String userId) {
        return MonitorStatusEvent.newBuilder()
            .setEventId("event-1")
            .setMonitorId("monitor-1")
            .setUserId(userId)
            .setEventType(EventType.MONITOR_DOWN)
            .setTargetUrl("https://example.com")
            .setOccurredAt(1_700_000_000_000L)
            .setDetails("ConnectException")
            .build();
    }
}
