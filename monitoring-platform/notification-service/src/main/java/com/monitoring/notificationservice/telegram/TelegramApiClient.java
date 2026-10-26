package com.monitoring.notificationservice.telegram;

import com.monitoring.notificationservice.telegram.TelegramModels.ApiResponse;
import com.monitoring.notificationservice.telegram.TelegramModels.Update;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Тонкий клиент Telegram Bot API поверх RestClient. Отдельная библиотека для
 * бота здесь не нужна: нам требуются ровно два метода, getUpdates и sendMessage.
 */
@Component
public class TelegramApiClient {

    private final String token;
    private final RestClient restClient;

    public TelegramApiClient(@Value("${telegram.bot-token:}") String token) {
        this.token = token;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        // Таймаут чтения ОБЯЗАН быть больше таймаута long polling (30 с):
        // при long polling Telegram намеренно держит запрос открытым,
        // пока не появится обновление или не истечёт его таймаут.
        requestFactory.setReadTimeout(40_000);

        this.restClient = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + token)
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }

    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        try {
            ApiResponse<List<Update>> response = restClient.get()
                    .uri("/getUpdates?offset={offset}&timeout={timeout}", offset, timeoutSeconds)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<List<Update>>>() {
                    });

            if (response == null || !response.ok() || response.result() == null) {
                throw new TelegramApiException("getUpdates вернул неуспешный ответ: "
                        + (response == null ? "пустой ответ" : response.description()));
            }
            return response.result();
        } catch (RestClientException e) {
            throw new TelegramApiException("getUpdates: " + sanitize(e.getMessage()));
        }
    }

    public void sendMessage(long chatId, String text) {
        try {
            restClient.post()
                    .uri("/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            // Постоянными считаем только 403 (бот заблокирован пользователем,
            // пользователь удалён) и "chat not found". 401 (неверный токен
            // бота) и 404 — это НАША ошибка конфигурации: из-за неё нельзя
            // отключать каналы пользователей, поэтому они остаются временными.
            boolean permanent = status == 403 || (status == 400 && body.contains("chat not found"));
            throw new TelegramApiException("sendMessage: HTTP " + status + " " + sanitize(body), permanent);
        } catch (RestClientException e) {
            throw new TelegramApiException("sendMessage: " + sanitize(e.getMessage()));
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return "(без сообщения)";
        }
        return token.isBlank() ? message : message.replace(token, "***");
    }
}
