package com.monitoring.webui.views;

import com.monitoring.webui.client.NotificationApiClient;
import com.monitoring.webui.client.NotificationApiClient.EmailLinkInvalidException;
import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.EmailConfirmedDto;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Страница, на которую ведёт ссылка из письма подтверждения. Доступна без входа: человек
 * может открыть письмо в другом браузере или на другом устройстве, а учётными данными
 * служит сам одноразовый токен в ссылке.
 * Адрес подтверждается НЕ при открытии страницы, а по нажатию кнопки. Почтовые сервисы
 * и антивирусы часто сами открывают ссылки из писем для проверки: если бы подтверждение
 * срабатывало при открытии, токен сгорал бы раньше, чем человек увидит письмо.
 * Автоматические проверщики кнопки не нажимают.
 */
@Route("verify-email")
@PageTitle("Подтверждение адреса — Monitoring Platform")
@AnonymousAllowed
public class VerifyEmailView extends VerticalLayout implements BeforeEnterObserver {

    private final NotificationApiClient notificationApi;

    public VerifyEmailView(NotificationApiClient notificationApi) {
        this.notificationApi = notificationApi;

        setPadding(true);
        setMaxWidth("520px");
        getStyle().set("margin", "0 auto");
        setAlignItems(Alignment.CENTER);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        String token = event.getLocation().getQueryParameters().getSingleParameter("token").orElse("");

        add(new H2("Подтверждение адреса"));

        if (token.isBlank()) {
            add(new Paragraph("В ссылке нет кода подтверждения. Откройте ссылку из письма целиком "
                    + "или запросите новое письмо в настройках."),
                settingsLink());
            return;
        }

        Paragraph info = new Paragraph(
            "Нажмите кнопку, чтобы подтвердить адрес электронной почты для уведомлений Monitoring Platform.");
        Button confirmButton = new Button("Подтвердить адрес");
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        confirmButton.addClickListener(e -> confirm(token, confirmButton));
        add(info, confirmButton);
    }

    private void confirm(String token, Button confirmButton) {
        // Защита от повторного нажатия, пока идёт запрос
        confirmButton.setEnabled(false);
        try {
            EmailConfirmedDto result = notificationApi.confirmEmail(token);
            removeAll();
            add(new H2("Адрес подтверждён"),
                new Paragraph("Уведомления будут приходить на " + result.destinationHint()
                    + ". Управлять ими можно в настройках."),
                settingsLink());
        } catch (EmailLinkInvalidException e) {
            removeAll();
            add(new H2("Ссылка недействительна"),
                new Paragraph("Ссылка устарела или уже была использована. "
                    + "Запросите новое письмо в настройках."),
                settingsLink());
        } catch (NotificationApiException e) {
            confirmButton.setEnabled(true);
            Notification notification = Notification.show(
                "Не удалось подтвердить адрес. Попробуйте ещё раз позже.", 4000, Notification.Position.MIDDLE);
            notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private RouterLink settingsLink() {
        // Незалогиненного пользователя настройки перенаправят на вход
        return new RouterLink("Перейти в настройки", SettingsView.class);
    }
}
