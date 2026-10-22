package com.monitoring.webui.security;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * VaadinWebSecurity — базовый класс от самого Vaadin, который уже настраивает
 * то, что нужно Vaadin-приложению по умолчанию (доступ к статике фреймворка,
 * обработку Vaadin-специфичных запросов). Мы наследуемся и добавляем сверху
 * OAuth2 Login — Vaadin сам это не делает, это наша ответственность.
 *
 * В отличие от core-api (oauth2ResourceServer + JWT-валидация, STATELESS),
 * здесь oauth2Login(): браузер редиректится на Keycloak, после успешного
 * логина Keycloak возвращает браузер обратно с authorization code, Spring
 * Security сам обменивает его на токены и создаёт HTTP-сессию — то есть
 * именно тот, stateful, механизм, который в core-api мы намеренно отключили.
 */
@Configuration
public class SecurityConfig extends VaadinWebSecurity {

    private final ClientRegistrationRepository clientRegistrationRepository;

    public SecurityConfig(ClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http); // база от Vaadin — обязательно вызывать первой

        // Известная несовместимость Vaadin с дефолтной CSRF-стратегией
        // Spring Security 6: там токен вычисляется ЛЕНИВО (deferred, ради
        // защиты от BREACH-атак) — реально сохраняется в сессию, только
        // когда что-то явно обращается к CsrfToken.getToken(). Vaadin же
        // встраивает токен в bootstrap-страницу напрямую, ожидая немедленного
        // (eager) вычисления. Из-за рассинхронизации токен, который видит
        // клиент, не совпадает с тем, что Spring Security ожидает на
        // следующем запросе — отсюда "Invalid CSRF token" на КАЖДОМ UIDL-
        // запросе. Форсируем eager-резолвинг явно.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(csrfHandler));

        http.oauth2Login(oauth2 -> oauth2
                .loginPage("/oauth2/authorization/keycloak")
        );

        http.logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessHandler(oidcLogoutSuccessHandler())
        );
    }

    /**
     * При логауте из web-ui недостаточно просто уничтожить локальную HTTP-сессию —
     * у Keycloak своя независимая сессия (SSO cookie в браузере на домене Keycloak).
     * Если её не закрыть тоже, при следующем заходе на защищённую страницу
     * пользователь молча залогинится обратно без повторного ввода пароля.
     * OidcClientInitiatedLogoutSuccessHandler явно редиректит в Keycloak
     * end_session_endpoint, закрывая сессию там же.
     */
    private LogoutSuccessHandler oidcLogoutSuccessHandler() {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}
