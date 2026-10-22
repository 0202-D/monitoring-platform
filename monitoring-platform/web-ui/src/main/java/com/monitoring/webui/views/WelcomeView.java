package com.monitoring.webui.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Корневой маршрут. НЕ использует MainLayout — неавторизованному
 * посетителю нечего делать с nav-баром, у которого есть только кнопка
 * "Выйти" (MainLayout заточен под уже залогиненного пользователя).
 *
 * @AnonymousAllowed обязателен: без него Vaadin's ViewAccessChecker закроет
 * доступ вообще всем (см. аналогичный комментарий в RegistrationView) —
 * тогда до этой страницы никто бы не дошёл, redirect на Keycloak срабатывал
 * бы раньше, чем Vaadin успел бы её отрендерить.
 */
@Route("")
@PageTitle("Monitoring Platform")
@AnonymousAllowed
public class WelcomeView extends VerticalLayout implements BeforeEnterObserver {

    public WelcomeView() {
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        H1 title = new H1("Monitoring Platform");
        Paragraph subtitle = new Paragraph(
                "Мониторинг аптайма, SSL-сертификатов и доменов — просто и надёжно.");

        // Anchor, а не RouterLink: это переход на URL ВНЕ системы маршрутов
        // Vaadin (Spring Security перехватывает GET /oauth2/authorization/keycloak
        // и делает full-page redirect на Keycloak) — RouterLink попытался бы
        // обработать это как client-side SPA-навигацию и не сработал бы.
        Anchor loginLink = new Anchor("/oauth2/authorization/keycloak", "");
        Button loginButton = new Button("Войти");
        loginButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        loginLink.add(loginButton);
        loginLink.getElement().setAttribute("router-ignore", true);

        // RegistrationView — обычный Vaadin route в этом же приложении,
        // поэтому тут уместна client-side навигация без полной перезагрузки.
        Button registerButton = new Button("Зарегистрироваться");
        registerButton.addClickListener(e -> UI.getCurrent().navigate(RegistrationView.class));

        HorizontalLayout actions = new HorizontalLayout(loginLink, registerButton);
        actions.setSpacing(true);

        add(title, subtitle, actions);
    }

    /**
     * Если пользователь уже аутентифицирован (валидная сессия, зашёл на "/"
     * повторно — например, по закладке), незачем показывать ему приветствие
     * заново: сразу отправляем на его мониторы, как и ожидается от
     * "обычного" поведения приложения.
     */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());

        if (isAuthenticated) {
            event.forwardTo(MonitorsView.class);
        }
    }
}
