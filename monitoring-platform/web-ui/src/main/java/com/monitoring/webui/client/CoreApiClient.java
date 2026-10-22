package com.monitoring.webui.client;

import com.monitoring.webui.client.dto.CreateMonitorRequestDto;
import com.monitoring.webui.client.dto.MonitorDto;
import com.monitoring.webui.client.dto.UpdateMonitorRequestDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

/**
 * Здесь принципиально важно: токен, которым мы стучимся в core-api, — это
 * ТОТ ЖЕ access token, что Keycloak выдал пользователю при логине в web-ui
 * (см. OAuth2ClientConfig). core-api видит запрос от лица реального
 * пользователя (его sub claim), а не от какого-то отдельного технического
 * клиента — поэтому JIT-провижининг и лимит FREE-плана в core-api работают
 * ровно так же, как если бы пользователь сам дёрнул API через curl со своим
 * токеном.
 */
@Component
public class CoreApiClient {

    private final RestClient restClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public CoreApiClient(
            @Value("${core-api.base-url}") String coreApiBaseUrl,
            OAuth2AuthorizedClientManager authorizedClientManager) {
        this.restClient = RestClient.builder().baseUrl(coreApiBaseUrl).build();
        this.authorizedClientManager = authorizedClientManager;
    }

    public List<MonitorDto> listMonitors() {
        try {
            return restClient.get()
                .uri("/api/monitors")
                .headers(h -> h.setBearerAuth(currentAccessToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<List<MonitorDto>>() {});
        } catch (RestClientException e) {
            // RestClientException — общий родитель и для HTTP-ошибок
            // (RestClientResponseException, если core-api ответил 4xx/5xx),
            // и для сетевых сбоев (ResourceAccessException, если core-api
            // вообще недоступен — connection refused, таймаут). Ловить нужно
            // оба случая одинаково: пользователю всё равно, ЧТО пошло не так,
            // но необработанное исключение здесь роняет Vaadin UI-сессию
            // целиком (симптом "Connection lost" в браузере), а не просто
            // показывает нормальную ошибку.
            throw new CoreApiException("Не удалось загрузить список мониторов: " + e.getMessage(), e);
        }
    }

    public MonitorDto createMonitor(CreateMonitorRequestDto request) {
        try {
            return restClient.post()
                .uri("/api/monitors")
                .headers(h -> h.setBearerAuth(currentAccessToken()))
                .body(request)
                .retrieve()
                .body(MonitorDto.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(402)) {
                // 402 Payment Required — именно этот код возвращает
                // MonitorService.createMonitor() в core-api при достижении
                // лимита FREE-плана (см. код на предыдущем шаге).
                throw new CoreApiException(
                    "Достигнут лимит FREE-плана. Оформите PRO для увеличения лимита.", e);
            }
            throw new CoreApiException("Не удалось создать монитор: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // Сетевой сбой (core-api недоступен, таймаут) — отдельно от
            // HTTP-ошибок выше. Порядок catch-блоков важен: этот блок ловит
            // всё, что не поймал предыдущий (RestClientResponseException —
            // его подкласс).
            throw new CoreApiException("core-api недоступен: " + e.getMessage(), e);
        }
    }

    public MonitorDto updateMonitor(UUID id, UpdateMonitorRequestDto request) {
        try {
            return restClient.put()
                .uri("/api/monitors/{id}", id)
                .headers(h -> h.setBearerAuth(currentAccessToken()))
                .body(request)
                .retrieve()
                .body(MonitorDto.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                throw new CoreApiException("Монитор не найден или уже удалён", e);
            }
            throw new CoreApiException("Не удалось обновить монитор: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new CoreApiException("core-api недоступен: " + e.getMessage(), e);
        }
    }

    public void deleteMonitor(UUID id) {
        try {
            restClient.delete()
                .uri("/api/monitors/{id}", id)
                .headers(h -> h.setBearerAuth(currentAccessToken()))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                // Монитор уже удалён (например, в другой вкладке) —
                // для пользователя результат идентичен успешному удалению,
                // нет смысла показывать это как ошибку.
                return;
            }
            throw new CoreApiException("Не удалось удалить монитор: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new CoreApiException("core-api недоступен: " + e.getMessage(), e);
        }
    }

    /**
     * Достаёт access token текущего пользователя из HTTP-сессии, при
     * необходимости прозрачно обновляя его по refresh token
     * (см. OAuth2ClientConfig — .refreshToken() в цепочке провайдеров).
     */
    private String currentAccessToken() {
        var authentication = (OAuth2AuthenticationToken) SecurityContextHolder.getContext().getAuthentication();

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
            .withClientRegistrationId(authentication.getAuthorizedClientRegistrationId())
            .principal(authentication)
            .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new IllegalStateException("Не удалось получить access token текущего пользователя");
        }

        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
        return accessToken.getTokenValue();
    }

    public static class CoreApiException extends RuntimeException {
        public CoreApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
