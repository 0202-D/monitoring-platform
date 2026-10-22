package com.monitoring.webui.dto;

import java.util.List;
import java.util.Map;

/**
 * Тело запроса POST /admin/realms/{realm}/users — формат, который ожидает
 * Keycloak Admin REST API. Именно так, списком credential-объектов, а не
 * плоским полем "password" — так устроен протокол на стороне Keycloak.
 */
public record CreateKeycloakUserRequest(
    String username,
    String email,
    String firstName,
    String lastName,
    boolean enabled,
    boolean emailVerified,
    List<Credential> credentials
) {
    public record Credential(String type, String value, boolean temporary) {
    }

    public static CreateKeycloakUserRequest of(String username, String email,
                                                 String firstName, String lastName,
                                                 String password) {
        return new CreateKeycloakUserRequest(
            username, email, firstName, lastName,
            true, true,
            List.of(new Credential("password", password, false))
        );
    }
}
