package com.monitoring.webui.views;

import com.monitoring.webui.client.NotificationApiClient;
import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.TelegramLinkDto;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;

import java.time.Instant;

@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Настройки — Monitoring Platform")
@PermitAll
public class SettingsView extends VerticalLayout {

    private static final String TELEGRAM = "TELEGRAM";

    // Пока ждём, что пользователь нажмёт Start в Telegram, опрашиваем состояние
    // каждые 3 секунды. Опрос идёт ТОЛЬКО во время ожидания: интервал опроса
    // относится ко всему UI, держать его включённым постоянно незачем.
    private static final int LINK_POLL_INTERVAL_MS = 3_000;

    private enum TelegramState { NOT_CONNECTED, CONNECTED, DISABLED }

    private final NotificationApiClient notificationApi;

    private final Span statusBadge = new Span();
    private final Paragraph statusText = new Paragraph();
    private final Button connectButton = new Button("Подключить Telegram");
    private final Button disconnectButton = new Button("Отключить");
    private final VerticalLayout linkPanel = new VerticalLayout();

    private Registration pollRegistration;
    // не null, пока мы ждём привязки по выданной ссылке
    private Instant waitingUntil;

    public SettingsView(NotificationApiClient notificationApi) {
        this.notificationApi = notificationApi;

        setPadding(true);
        setMaxWidth("640px");

        add(new H2("Уведомления"), buildTelegramCard());
        refreshStatus();
    }

    private VerticalLayout buildTelegramCard() {
        statusBadge.getElement().getThemeList().add("badge");

        HorizontalLayout titleRow = new HorizontalLayout(new H3("Telegram"), statusBadge);
        titleRow.setAlignItems(Alignment.CENTER);

        connectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connectButton.addClickListener(e -> requestLink());

        disconnectButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        disconnectButton.addClickListener(e -> confirmDisconnect());

        linkPanel.setPadding(false);
        linkPanel.setVisible(false);

        VerticalLayout card = new VerticalLayout(
            titleRow, statusText, new HorizontalLayout(connectButton, disconnectButton), linkPanel);
        card.setPadding(false);
        return card;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Слушатель регистрируется сразу, а сам опрос включается позже,
        // только когда выдана ссылка (см. startWaiting)
        pollRegistration = attachEvent.getUI().addPollListener(e -> onPoll());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        if (waitingUntil != null) {
            waitingUntil = null;
            detachEvent.getUI().setPollInterval(-1);
        }
        super.onDetach(detachEvent);
    }

    private void requestLink() {
        try {
            TelegramLinkDto link = notificationApi.createTelegramLink();

            Anchor open = new Anchor(link.url(), "Открыть Telegram");
            open.setTarget("_blank");
            open.getElement().setAttribute("rel", "noopener noreferrer");
            // Vaadin-роутер не должен перехватывать клик по ссылке
            open.getElement().setAttribute("router-ignore", true);

            Paragraph hint = new Paragraph(
                "Откройте ссылку и нажмите Start в Telegram. "
                    + "Ссылка действует 10 минут и срабатывает один раз.");

            linkPanel.removeAll();
            linkPanel.add(open, hint);
            linkPanel.setVisible(true);

            startWaiting(link.expiresAt());
        } catch (NotificationApiException e) {
            showError(e.getMessage());
        }
    }

    private void startWaiting(Instant expiresAt) {
        waitingUntil = expiresAt;
        getUI().ifPresent(ui -> ui.setPollInterval(LINK_POLL_INTERVAL_MS));
    }

    private void stopWaiting() {
        waitingUntil = null;
        linkPanel.setVisible(false);
        getUI().ifPresent(ui -> ui.setPollInterval(-1));
    }

    /**
     * Выполняется как обычный HTTP-запрос poll через цепочку Spring Security,
     * поэтому токен пользователя доступен (в фоновом потоке был бы недоступен).
     */
    private void onPoll() {
        if (waitingUntil == null) {
            return;
        }
        try {
            TelegramState state = loadState();
            render(state);
            if (state == TelegramState.CONNECTED) {
                stopWaiting();
                showSuccess("Telegram подключён");
                return;
            }
        } catch (NotificationApiException e) {
            // Фоновый опрос не засыпает пользователя ошибками: следующий опрос повторит попытку
        }
        if (Instant.now().isAfter(waitingUntil)) {
            stopWaiting();
            showError("Срок действия ссылки истёк. Создайте новую.");
        }
    }

    private void confirmDisconnect() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Отключить Telegram?");
        confirm.setText("Уведомления перестанут приходить в Telegram. Подключить его можно снова в любой момент.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Отключить");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                notificationApi.unlinkTelegram();
                refreshStatus();
                showSuccess("Telegram отключён");
            } catch (NotificationApiException ex) {
                showError(ex.getMessage());
            }
        });
        confirm.open();
    }

    private TelegramState loadState() {
        return notificationApi.listChannels().stream()
            .filter(channel -> TELEGRAM.equals(channel.channelType()))
            .findFirst()
            .map(channel -> channel.active() ? TelegramState.CONNECTED : TelegramState.DISABLED)
            .orElse(TelegramState.NOT_CONNECTED);
    }

    private void refreshStatus() {
        try {
            render(loadState());
        } catch (NotificationApiException e) {
            setBadge("Нет данных", "contrast");
            statusText.setText("Не удалось загрузить состояние Telegram. Попробуйте обновить страницу.");
            connectButton.setVisible(false);
            disconnectButton.setVisible(false);
        }
    }

    private void render(TelegramState state) {
        switch (state) {
            case CONNECTED -> {
                setBadge("Подключён", "success");
                statusText.setText("Уведомления о смене статуса мониторов приходят в ваш Telegram.");
                connectButton.setVisible(false);
                disconnectButton.setVisible(true);
            }
            case DISABLED -> {
                setBadge("Отключён", "error");
                statusText.setText("Telegram не смог доставить сообщение (бот заблокирован или чат "
                    + "недоступен). Подключите его заново.");
                connectButton.setVisible(true);
                disconnectButton.setVisible(true);
            }
            case NOT_CONNECTED -> {
                setBadge("Не подключён", "contrast");
                statusText.setText("Подключите Telegram, чтобы получать уведомления о падении "
                    + "и восстановлении мониторов.");
                connectButton.setVisible(true);
                disconnectButton.setVisible(false);
            }
        }
    }

    private void setBadge(String text, String variant) {
        statusBadge.setText(text);
        statusBadge.getElement().getThemeList().clear();
        statusBadge.getElement().getThemeList().add("badge");
        statusBadge.getElement().getThemeList().add(variant);
    }

    private void showError(String message) {
        Notification notification = Notification.show(message, 4000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showSuccess(String message) {
        Notification notification = Notification.show(message, 3000, Notification.Position.BOTTOM_END);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
