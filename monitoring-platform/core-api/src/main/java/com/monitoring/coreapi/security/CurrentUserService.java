package com.monitoring.coreapi.security;

import com.monitoring.coreapi.entity.AppUser;
import com.monitoring.coreapi.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Keycloak — единственный источник правды по identity (логин/пароль/MFA),
 * но core-api всё равно нужна своя строка app_user, чтобы иметь на что
 * ссылаться внешним ключом из monitor и хранить продуктовые атрибуты (plan и т.д.),
 * которых в Keycloak нет и не должно быть.
 *
 * JIT (just-in-time) provisioning: при первом запросе с валидным токеном от
 * ещё не встречавшегося subject'а — создаём запись. Никакого отдельного
 * "шага регистрации" в core-api не нужно, Keycloak уже всё проверил.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final AppUserRepository appUserRepository;

    @Transactional
    public AppUser resolveOrCreate(Jwt jwt) {
        String subject = jwt.getSubject();
        return appUserRepository.findByKeycloakSubject(subject)
            .orElseGet(() -> {
                String email = jwt.getClaimAsString("email");
                AppUser newUser = new AppUser(UUID.randomUUID(), subject, email);
                return appUserRepository.save(newUser);
            });
    }
}
