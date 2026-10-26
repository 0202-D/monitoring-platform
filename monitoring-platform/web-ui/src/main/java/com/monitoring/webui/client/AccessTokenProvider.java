package com.monitoring.webui.client;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Источник access token ТЕКУЩЕГО пользователя для исходящих запросов к другим
 * сервисам (core-api, notification-service). Это тот же токен, что Keycloak
 * выдал при логине в web-ui: сервисы видят запрос от лица реального
 * пользователя (его sub), а не от технического клиента.
 *
 * Токен берётся из SecurityContext, поэтому метод работает только в потоке
 * HTTP-запроса (обработчики кликов, poll-события Vaadin) и не работает в
 * фоновых потоках, где контекст безопасности пуст.
 */
@Component
public class AccessTokenProvider {

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public AccessTokenProvider(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    /**
     * Достаёт access token из HTTP-сессии, при необходимости прозрачно
     * обновляя его по refresh token (см. OAuth2ClientConfig).
     */
    public String currentAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            throw new IllegalStateException("Нет аутентифицированного пользователя для получения токена");
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
            .withClientRegistrationId(oauthToken.getAuthorizedClientRegistrationId())
            .principal(oauthToken)
            .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new IllegalStateException("Не удалось получить access token текущего пользователя");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
