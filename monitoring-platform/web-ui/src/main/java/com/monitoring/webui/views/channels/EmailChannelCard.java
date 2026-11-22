package com.monitoring.webui.views.channels;

import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.ChannelDto;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.textfield.EmailField;

/**
 * Email-канал: адрес вводится пользователем и становится рабочим только после подтверждения
 * по ссылке из письма. Пока адрес не подтверждён, уведомления на почту не приходят,
 * и при смене адреса старый перестаёт получать письма сразу.
 */
public class EmailChannelCard extends ChannelCard {

    private enum State { NOT_CONNECTED, PENDING, ENABLED, PAUSED, BROKEN }

    private final EmailField addressField = new EmailField("Адрес электронной почты");
    private final Button sendButton = new Button("Отправить письмо");
    private final Button changeButton = new Button("Изменить адрес");
    private final Button cancelButton = new Button("Отмена");
    private final Button disconnectButton = new Button("Отвязать");

    private State state = State.NOT_CONNECTED;
    // пользователь нажал "Изменить адрес" и видит поле ввода
    private boolean editing;

    public EmailChannelCard(CardHost host) {
        super(host, "Email", "Получать уведомления на email");

        addressField.setWidthFull();
        addressField.setClearButtonVisible(true);
        addressField.setVisible(false);
        addAboveActions(addressField);

        sendButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        sendButton.addClickListener(e -> send());

        changeButton.addClickListener(e -> {
            editing = true;
            applyVisibility();
        });

        cancelButton.addClickListener(e -> {
            editing = false;
            addressField.clear();
            applyVisibility();
        });

        disconnectButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        disconnectButton.addClickListener(e -> confirmDisconnect());

        actions().add(sendButton, changeButton, cancelButton, disconnectButton);
    }

    @Override
    protected String channelType() {
        return "EMAIL";
    }

    @Override
    public boolean isWaiting() {
        return state == State.PENDING;
    }

    @Override
    public void render(ChannelDto channel) {
        State previous = state;
        state = toState(channel);
        if (state != previous) {
            editing = false;
        }
        String hint = channel != null && channel.destinationHint() != null ? channel.destinationHint() : "";

        switch (state) {
            case NOT_CONNECTED -> {
                setBadge("Не подключён", "contrast");
                setStatusText("Укажите адрес, чтобы получать уведомления на почту. "
                    + "Мы отправим письмо со ссылкой подтверждения.");
                hideToggle();
            }
            case PENDING -> {
                setBadge("Ждёт подтверждения", "warning");
                setStatusText("Мы отправили письмо на " + hint + ". Откройте его и перейдите по ссылке. "
                    + "Пока адрес не подтверждён, уведомления на почту не приходят.");
                hideToggle();
            }
            case ENABLED -> {
                setBadge("Включено", "success");
                setStatusText("Уведомления приходят на " + hint + ".");
                showToggle(true);
            }
            case PAUSED -> {
                setBadge("Выключено", "contrast");
                setStatusText("Вы выключили уведомления на email (" + hint + "). "
                    + "Включите переключатель, чтобы снова их получать.");
                showToggle(false);
            }
            case BROKEN -> {
                setBadge("Требует внимания", "error");
                String reason = channel.statusReason();
                setStatusText((reason != null ? reason : "Доставка на этот адрес невозможна")
                    + ". Укажите адрес заново.");
                hideToggle();
            }
        }

        // Адрес подтверждён в другой вкладке или на другом устройстве
        if (previous == State.PENDING && state == State.ENABLED) {
            host.success("Адрес подтверждён: " + hint);
        }
        applyVisibility();
    }

    private State toState(ChannelDto channel) {
        if (channel == null) {
            return State.NOT_CONNECTED;
        }
        if ("BROKEN".equals(channel.status())) {
            return State.BROKEN;
        }
        if ("PENDING_VERIFICATION".equals(channel.status())) {
            return State.PENDING;
        }
        return channel.enabled() ? State.ENABLED : State.PAUSED;
    }

    private void applyVisibility() {
        boolean fieldNeeded = state == State.NOT_CONNECTED || state == State.BROKEN || editing;
        addressField.setVisible(fieldNeeded);
        sendButton.setVisible(fieldNeeded);
        cancelButton.setVisible(editing);

        boolean hasAddress = state == State.PENDING || state == State.ENABLED || state == State.PAUSED;
        changeButton.setVisible(hasAddress && !editing);
        changeButton.setText(state == State.PENDING ? "Указать адрес заново" : "Изменить адрес");
        disconnectButton.setVisible(state != State.NOT_CONNECTED);

        addressField.setHelperText(hasAddress
            ? "Пока новый адрес не подтверждён, уведомления на почту приходить не будут"
            : "Мы отправим на этот адрес письмо со ссылкой подтверждения");
    }

    private void send() {
        String address = addressField.getValue() == null ? "" : addressField.getValue().trim();
        if (address.isEmpty()) {
            host.error("Укажите адрес электронной почты");
            return;
        }
        try {
            host.api().requestEmailVerification(address);
        } catch (NotificationApiException e) {
            host.error(e.getMessage());
            return;
        }
        addressField.clear();
        editing = false;
        host.success("Письмо отправлено. Проверьте почту.");
        // Страница включит опрос, пока адрес ждёт подтверждения
        host.refresh();
    }

    private void confirmDisconnect() {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Отвязать email?");
        confirm.setText("Уведомления перестанут приходить на почту. Адрес можно указать снова в любой момент.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Отвязать");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                host.api().unlinkChannel("email");
                host.refresh();
                host.success("Email отвязан");
            } catch (NotificationApiException ex) {
                host.error(ex.getMessage());
            }
        });
        confirm.open();
    }
}
