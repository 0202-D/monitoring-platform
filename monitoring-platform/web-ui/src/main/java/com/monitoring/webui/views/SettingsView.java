package com.monitoring.webui.views;

import com.monitoring.webui.client.NotificationApiClient;
import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.ChannelDto;
import com.monitoring.webui.client.dto.TelegramLinkDto;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
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

    /**
     * Состояние карточки Telegram. Три независимых факта (привязан ли канал,
     * выбор пользователя, состояние системы) сводятся к четырём случаям.
     */
    private enum TelegramState {
        NOT_CONNECTED,  // канал не привязан
        ENABLED,        // привязан, включён, исправен
        PAUSED,         // привязан, пользователь выключил переключатель
        BROKEN          // система выяснила, что доставка невозможна
    }

    private final NotificationApiClient notificationApi;

    private final Span statusBadge = new Span();
    private final Paragraph statusText = new Paragraph();
    private final Checkbox notifyToggle = new Checkbox("Получать уведомления в Telegram");
    private final Button connectButton = new Button("Подключить Telegram");
    private final Button disconnectButton = new Button("Отвязать");
    private final VerticalLayout linkPanel = new VerticalLayout();

    private Registration pollRegistration;
    // не null, пока мы ждём привязки по выданной ссылке
    private Instant waitingUntil;
    // причина, по которой система пометила канал сломанным (для показа пользователю)
    private String brokenReason;

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

        // isFromClient: реагируем только на действие пользователя, а не на программную
        // установку значения (иначе откат значения при ошибке вызвал бы повторный запрос)
        notifyToggle.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                onToggle(event.getValue());
            }
        });

        connectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connectButton.addClickListener(e -> requestLink());

        disconnectButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        disconnectButton.addClickListener(e -> confirmDisconnect());

        linkPanel.setPadding(false);
        linkPanel.setVisible(false);

        VerticalLayout card = new VerticalLayout(
            titleRow, statusText, notifyToggle,
            new HorizontalLayout(connectButton, disconnectButton), linkPanel);
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

    private void onToggle(boolean enabled) {
        try {
            notificationApi.setChannelEnabled(TELEGRAM, enabled);
            refreshStatus();
            showSuccess(enabled ? "Уведомления в Telegram включены" : "Уведомления в Telegram выключены");
        } catch (NotificationApiException e) {
            // Откатываем переключатель: на сервере значение не изменилось
            notifyToggle.setValue(!enabled);
            showError(e.getMessage());
        }
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
            // Повторная привязка включает канал и снимает пометку "сломан",
            // поэтому успехом считаем состояние ENABLED
            if (state == TelegramState.ENABLED) {
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
        confirm.setHeader("Отвязать Telegram?");
        confirm.setText("Уведомления перестанут приходить в Telegram. Подключить его можно снова в любой момент.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Отвязать");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                notificationApi.unlinkTelegram();
                refreshStatus();
                showSuccess("Telegram отвязан");
            } catch (NotificationApiException ex) {
                showError(ex.getMessage());
            }
        });
        confirm.open();
    }

    private TelegramState loadState() {
        brokenReason = null;
        return notificationApi.listChannels().stream()
            .filter(channel -> TELEGRAM.equals(channel.channelType()))
            .findFirst()
            .map(this::toState)
            .orElse(TelegramState.NOT_CONNECTED);
    }

    private TelegramState toState(ChannelDto channel) {
        // Состояние системы важнее выбора пользователя: сломанный канал
        // не работает, как бы ни стоял переключатель
        if ("BROKEN".equals(channel.status())) {
            brokenReason = channel.statusReason();
            return TelegramState.BROKEN;
        }
        return channel.enabled() ? TelegramState.ENABLED : TelegramState.PAUSED;
    }

    private void refreshStatus() {
        try {
            render(loadState());
        } catch (NotificationApiException e) {
            setBadge("Нет данных", "contrast");
            statusText.setText("Не удалось загрузить состояние Telegram. Попробуйте обновить страницу.");
            notifyToggle.setVisible(false);
            connectButton.setVisible(false);
            disconnectButton.setVisible(false);
        }
    }

    private void render(TelegramState state) {
        switch (state) {
            case ENABLED -> {
                setBadge("Включено", "success");
                statusText.setText("Уведомления о смене статуса мониторов приходят в ваш Telegram.");
                showToggle(true);
                connectButton.setVisible(false);
                disconnectButton.setVisible(true);
            }
            case PAUSED -> {
                setBadge("Выключено", "contrast");
                statusText.setText("Вы выключили уведомления в Telegram. Включите переключатель, "
                    + "чтобы снова их получать.");
                showToggle(false);
                connectButton.setVisible(false);
                disconnectButton.setVisible(true);
            }
            case BROKEN -> {
                setBadge("Требует внимания", "error");
                statusText.setText(brokenReason != null
                    ? brokenReason + ". Подключите Telegram заново."
                    : "Доставка в Telegram невозможна. Подключите его заново.");
                notifyToggle.setVisible(false);
                connectButton.setText("Подключить заново");
                connectButton.setVisible(true);
                disconnectButton.setVisible(true);
            }
            case NOT_CONNECTED -> {
                setBadge("Не подключён", "contrast");
                statusText.setText("Подключите Telegram, чтобы получать уведомления о падении "
                    + "и восстановлении мониторов.");
                notifyToggle.setVisible(false);
                connectButton.setText("Подключить Telegram");
                connectButton.setVisible(true);
                disconnectButton.setVisible(false);
            }
        }
    }

    private void showToggle(boolean value) {
        notifyToggle.setVisible(true);
        // программная установка: слушатель её игнорирует (isFromClient = false)
        notifyToggle.setValue(value);
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
