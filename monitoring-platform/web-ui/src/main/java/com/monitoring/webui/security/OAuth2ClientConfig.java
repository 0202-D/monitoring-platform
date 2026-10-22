package com.monitoring.webui.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Spring Boot НЕ создаёт OAuth2AuthorizedClientManager автоматически для
 * servlet-приложений (в отличие от reactive-стека) — его нужно завести самим.
 *
 * Назначение: по текущему аутентифицированному пользователю достать его
 * access token, который Spring Security получил при логине через Keycloak
 * и хранит в HTTP-сессии (через автонастроенный OAuth2AuthorizedClientRepository).
 * Этим токеном CoreApiClient будет представляться перед core-api — то есть
 * core-api видит запрос от лица ТОГО ЖЕ пользователя, что залогинен в web-ui,
 * а не от какого-то отдельного технического клиента.
 *
 * refreshToken() в цепочке провайдеров критичен: access token живёт недолго
 * (обычно минуты), без автоматического обновления по refresh token
 * пользователь начал бы получать 401 от core-api при каждой сессии длиннее
 * времени жизни токена — совершенно не то поведение, которое ожидаешь от
 * залогиненного веб-приложения.
 */
@Configuration
public class OAuth2ClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {

        OAuth2AuthorizedClientProvider authorizedClientProvider =
            OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build();

        DefaultOAuth2AuthorizedClientManager manager =
            new DefaultOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientRepository);
        manager.setAuthorizedClientProvider(authorizedClientProvider);

        return manager;
    }
}
