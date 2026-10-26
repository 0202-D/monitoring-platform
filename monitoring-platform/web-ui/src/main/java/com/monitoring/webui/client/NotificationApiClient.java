package com.monitoring.webui.client;

import com.monitoring.webui.client.dto.ChannelDto;
import com.monitoring.webui.client.dto.TelegramLinkDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/** Клиент notification-service: каналы уведомлений пользователя. */
@Component
public class NotificationApiClient {

    private final RestClient restClient;
    private final AccessTokenProvider accessTokenProvider;

    public NotificationApiClient(
            @Value("${notification-service.base-url}") String baseUrl,
            AccessTokenProvider accessTokenProvider) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.accessTokenProvider = accessTokenProvider;
    }

    public List<ChannelDto> listChannels() {
        try {
            List<ChannelDto> channels = restClient.get()
                .uri("/api/notification-channels")
                .headers(h -> h.setBearerAuth(accessTokenProvider.currentAccessToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ChannelDto>>() {});
            return channels == null ? List.of() : channels;
        } catch (RestClientException e) {
            throw new NotificationApiException("Не удалось загрузить каналы уведомлений: " + e.getMessage(), e);
        }
    }

    public TelegramLinkDto createTelegramLink() {
        try {
            TelegramLinkDto link = restClient.post()
                .uri("/api/notification-channels/telegram/link")
                .headers(h -> h.setBearerAuth(accessTokenProvider.currentAccessToken()))
                .retrieve()
                .body(TelegramLinkDto.class);
            if (link == null) {
                throw new NotificationApiException("Сервис уведомлений вернул пустой ответ", null);
            }
            return link;
        } catch (RestClientException e) {
            throw new NotificationApiException("Не удалось создать ссылку для Telegram: " + e.getMessage(), e);
        }
    }

    public void unlinkTelegram() {
        try {
            restClient.delete()
                .uri("/api/notification-channels/telegram")
                .headers(h -> h.setBearerAuth(accessTokenProvider.currentAccessToken()))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                // Уже отключён (например, в другой вкладке): для пользователя
                // результат тот же, что и при успешном отключении.
                return;
            }
            throw new NotificationApiException("Не удалось отключить Telegram: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new NotificationApiException("Не удалось отключить Telegram: " + e.getMessage(), e);
        }
    }

    public static class NotificationApiException extends RuntimeException {
        public NotificationApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
