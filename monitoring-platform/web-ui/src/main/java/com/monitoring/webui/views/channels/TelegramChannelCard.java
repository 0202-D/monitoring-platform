package com.monitoring.webui.views.channels;

import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.ChannelDto;
import com.monitoring.webui.client.dto.TelegramLinkDto;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.time.Instant;

public class TelegramChannelCard extends ChannelCard {

    private enum State { NOT_CONNECTED, ENABLED, PAUSED, BROKEN }

    private final Button connectButton = new Button("Подключить Telegram");
    private final Button disconnectButton = new Button("Отвязать");
    private final VerticalLayout linkPanel = new VerticalLayout();

    // не null, пока мы ждём привязки по выданной ссылке
    private Instant waitingUntil;

    public TelegramChannelCard(CardHost host) {
        super(host, "Telegram", "Получать уведомления в Telegram");

        connectButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connectButton.addClickListener(e -> requestLink());

        disconnectButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        disconnectButton.addClickListener(e -> confirmDisconnect());

        actions().add(connectButton, disconnectButton);

        linkPanel.setPadding(false);
        linkPanel.setVisible(false);
        add(linkPanel);
    }

    @Override
    protected String channelType() {
        return "TELEGRAM";
    }

    @Override
    public boolean isWaiting() {
        return waitingUntil != null;
    }

    @Override
    public void render(ChannelDto channel) {
        State state = toState(channel);

        switch (state) {
            case ENABLED -> {
                setBadge("Включено", "success");
                setStatusText("Уведомления о смене статуса мониторов приходят в ваш Telegram.");
                showToggle(true);
                connectButton.setVisible(false);
                disconnectButton.setVisible(true);
            }
            case PAUSED -> {
                setBadge("Выключено", "contrast");
                setStatusText("Вы выключили уведомления в Telegram. Включите переключатель, "
                    + "чтобы снова их получать.");
                showToggle(false);
                connectButton.setVisible(false);
                disconnectButton.setVisible(true);
            }
            case BROKEN -> {
                setBadge("Требует внимания", "error");
                String reason = channel.statusReason();
                setStatusText(reason != null
                    ? reason + ". Подключите Telegram заново."
                    : "Доставка в Telegram невозможна. Подключите его заново.");
                hideToggle();
                connectButton.setText("Подключить заново");
                connectButton.setVisible(true);
                disconnectButton.setVisible(true);
            }
            case NOT_CONNECTED -> {
                setBadge("Не подключён", "contrast");
                setStatusText("Подключите Telegram, чтобы получать уведомления о падении "
                    + "и восстановлении мониторов.");
                hideToggle();
                connectButton.setText("Подключить Telegram");
                connectButton.setVisible(true);
                disconnectButton.setVisible(false);
            }
        }

        // Повторная привязка включает канал и снимает пометку "сломан",
        // поэтому успехом считаем состояние ENABLED
        if (waitingUntil != null) {
            if (state == State.ENABLED) {
                stopWaiting();
                host.success("Telegram подключён");
            } else if (Instant.now().isAfter(waitingUntil)) {
                stopWaiting();
                host.error("Срок действия ссылки истёк. Создайте новую.");
            }
        }
    }

    private State toState(ChannelDto channel) {
        if (channel == null) {
            return State.NOT_CONNECTED;
        }
        // Состояние системы важнее выбора пользователя: сломанный канал
        // не работает, как бы ни стоял переключатель
        if ("BROKEN".equals(channel.status())) {
            return State.BROKEN;
        }
        return channel.enabled() ? State.ENABLED : State.PAUSED;
    }

    private void requestLink() {
        try {
            TelegramLinkDto link = host.api().createTelegramLink();

            Anchor open = new Anchor(link.url(), "Открыть Telegram");
            open.setTarget("_blank");
            open.getElement().setAttribute("rel", "noopener noreferrer");
            // Vaadin-роутер не должен перехватывать клик по ссылке
            open.getElement().setAttribute("router-ignore", true);

            linkPanel.removeAll();
            linkPanel.add(open, new Paragraph(
                "Откройте ссылку и нажмите Start в Telegram. "
                    + "Ссылка действует 10 минут и срабатывает один раз."));
            linkPanel.setVisible(true);
            waitingUntil = link.expiresAt();
        } catch (NotificationApiException e) {
            host.error(e.getMessage());
            return;
        }
        // Страница включит опрос, пока карточка ждёт привязки
        host.refresh();
    }

    private void stopWaiting() {
        waitingUntil = null;
        linkPanel.setVisible(false);
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
                host.api().unlinkChannel("telegram");
                stopWaiting();
                host.refresh();
                host.success("Telegram отвязан");
            } catch (NotificationApiException ex) {
                host.error(ex.getMessage());
            }
        });
        confirm.open();
    }
}
