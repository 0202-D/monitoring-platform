package com.monitoring.webui.views;

import com.monitoring.webui.keycloak.KeycloakAdminClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * @AnonymousAllowed обязателен: без него VaadinWebSecurity по умолчанию
 * требует аутентификацию для ЛЮБОГО route, включая этот — что создало бы
 * абсурд "зарегистрируйтесь, но сначала войдите". См. также явный permitAll
 * на /register в SecurityConfig — Vaadin-аннотация управляет доступом на
 * уровне UI-роутинга, а SecurityConfig — на уровне HTTP-запроса (Vaadin
 * делает внутренние запросы к тому же URL при построении страницы),
 * оба должны быть согласованы, иначе один слой блокирует то, что разрешил другой.
 */
@Route("register")
@PageTitle("Регистрация — Monitoring Platform")
@AnonymousAllowed
public class RegistrationView extends VerticalLayout {

    private final KeycloakAdminClient keycloakAdminClient;

    public RegistrationView(KeycloakAdminClient keycloakAdminClient) {
        this.keycloakAdminClient = keycloakAdminClient;

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        H2 title = new H2("Регистрация");

        TextField username = new TextField("Имя пользователя");
        EmailField email = new EmailField("Email");
        TextField firstName = new TextField("Имя");
        TextField lastName = new TextField("Фамилия");
        PasswordField password = new PasswordField("Пароль");
        PasswordField passwordConfirm = new PasswordField("Повторите пароль");

        Button submit = new Button("Зарегистрироваться");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submit.addClickListener(e -> handleSubmit(
            username.getValue(), email.getValue(), firstName.getValue(),
            lastName.getValue(), password.getValue(), passwordConfirm.getValue()
        ));

        Anchor toLogin = new Anchor("/oauth2/authorization/keycloak", "Уже есть аккаунт? Войти");

        FormLayout form = new FormLayout(username, email, firstName, lastName, password, passwordConfirm);
        form.setMaxWidth("400px");

        VerticalLayout card = new VerticalLayout(title, form, submit, toLogin);
        card.setAlignItems(Alignment.STRETCH);
        card.setWidth("420px");

        add(card);
    }

    private void handleSubmit(String username, String email, String firstName,
                               String lastName, String password, String passwordConfirm) {
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            showError("Заполните обязательные поля: имя пользователя, email, пароль");
            return;
        }
        if (!password.equals(passwordConfirm)) {
            showError("Пароли не совпадают");
            return;
        }
        if (password.length() < 8) {
            showError("Пароль должен быть не короче 8 символов");
            return;
        }

        try {
            keycloakAdminClient.createUser(username, email, firstName, lastName, password);
            showSuccess("Регистрация прошла успешно! Теперь можно войти.");
        } catch (KeycloakAdminClient.UserRegistrationException e) {
            showError(e.getMessage());
        }
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 4000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showSuccess(String message) {
        Notification notification = Notification.show(message, 4000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
