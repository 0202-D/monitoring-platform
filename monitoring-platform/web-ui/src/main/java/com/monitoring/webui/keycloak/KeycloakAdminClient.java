package com.monitoring.webui.keycloak;

import com.monitoring.webui.dto.CreateKeycloakUserRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Keycloak — единственный источник правды по identity, поэтому "регистрация
 * пользователя" в этом приложении означает не запись в свою БД, а создание
 * пользователя НАПРЯМУЮ в Keycloak через его же Admin REST API. core-api
 * узнает об этом пользователе позже — при первом запросе с его JWT сработает
 * JIT-провижининг (CurrentUserService), который мы уже писали.
 *
 * Аутентификация к Admin API — Client Credentials grant отдельного service
 * account клиента (web-ui-admin-client) с ролью manage-users на клиенте
 * realm-management. НЕ используем токен залогиненного пользователя для этого:
 * у обычного пользователя нет и не должно быть прав создавать других
 * пользователей — это была бы избыточная привилегия, эскалация через
 * фронтенд-приложение.
 *
 * Используется RestClient (блокирующий, Spring Framework 6.1+), а не WebClient —
 * это не реактивное приложение (Vaadin работает поверх обычного Servlet-стека),
 * и тянуть spring-webflux ради двух синхронных HTTP-вызовов было бы лишней
 * зависимостью без реальной необходимости.
 */
@Component
@Slf4j
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final String tokenUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;

    public KeycloakAdminClient(
            @Value("${keycloak.admin.base-url}") String baseUrl,
            @Value("${keycloak.admin.realm}") String realm,
            @Value("${keycloak.admin.token-url}") String tokenUrl,
            @Value("${keycloak.admin.client-id}") String clientId,
            @Value("${keycloak.admin.client-secret}") String clientSecret) {
        this.baseUrl = baseUrl;
        this.realm = realm;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = RestClient.create();
    }

    /**
     * @throws UserRegistrationException если Keycloak отклонил запрос
     *         (чаще всего — username/email уже занят, HTTP 409)
     */
    public void createUser(String username, String email, String firstName,
                            String lastName, String password) {
        String accessToken = fetchServiceAccountToken();

        CreateKeycloakUserRequest request = CreateKeycloakUserRequest.of(
            username, email, firstName, lastName, password);

        try {
            restClient.post()
                .uri(baseUrl + "/admin/realms/{realm}/users", realm)
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.warn("Keycloak user creation failed: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            throw mapException(e);
        }
    }

    private String fetchServiceAccountToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        Map<String, Object> response = restClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak не вернул access_token для service account");
        }
        return (String) response.get("access_token");
    }

    private UserRegistrationException mapException(RestClientResponseException e) {
        if (e.getStatusCode() == HttpStatusCode.valueOf(409)) {
            return new UserRegistrationException(
                "Пользователь с таким именем или email уже существует", e);
        }
        return new UserRegistrationException(
            "Не удалось создать пользователя: " + e.getStatusCode(), e);
    }

    public static class UserRegistrationException extends RuntimeException {
        public UserRegistrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
