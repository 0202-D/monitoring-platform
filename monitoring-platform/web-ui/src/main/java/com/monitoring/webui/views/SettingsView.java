package com.monitoring.webui.views;

import com.monitoring.webui.client.NotificationApiClient;
import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.ChannelDto;
import com.monitoring.webui.views.channels.CardHost;
import com.monitoring.webui.views.channels.EmailChannelCard;
import com.monitoring.webui.views.channels.TelegramChannelCard;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Страница настроек уведомлений: по карточке на канал. Страница один раз загружает состояние
 * всех каналов и раздаёт его карточкам. Пока какая-то карточка ждёт внешнего действия
 * пользователя (нажать Start в Telegram, перейти по ссылке из письма), страница опрашивает
 * сервер каждые 3 секунды, иначе опрос выключен: интервал относится ко всему UI.
 */
@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Настройки — Monitoring Platform")
@PermitAll
public class SettingsView extends VerticalLayout implements CardHost {

    private static final int POLL_INTERVAL_MS = 3_000;

    private final NotificationApiClient notificationApi;
    private final TelegramChannelCard telegramCard;
    private final EmailChannelCard emailCard;

    private Registration pollRegistration;

    public SettingsView(NotificationApiClient notificationApi) {
        this.notificationApi = notificationApi;

        setPadding(true);
        setMaxWidth("640px");

        telegramCard = new TelegramChannelCard(this);
        emailCard = new EmailChannelCard(this);
        add(new H2("Уведомления"), telegramCard, emailCard);

        refresh();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Опрос выполняется как обычный HTTP-запрос через цепочку Spring Security,
        // поэтому токен пользователя доступен (в фоновом потоке был бы недоступен)
        pollRegistration = attachEvent.getUI().addPollListener(e -> load(true));
        updatePolling();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    @Override
    public NotificationApiClient api() {
        return notificationApi;
    }

    @Override
    public void refresh() {
        load(false);
    }

    @Override
    public void success(String message) {
        Notification notification = Notification.show(message, 3000, Notification.Position.BOTTOM_END);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    @Override
    public void error(String message) {
        Notification notification = Notification.show(message, 4000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void load(boolean quiet) {
        Map<String, ChannelDto> channels;
        try {
            channels = notificationApi.listChannels().stream()
                .collect(Collectors.toMap(ChannelDto::channelType, Function.identity()));
        } catch (NotificationApiException e) {
            // Фоновый опрос не засыпает пользователя ошибками и не стирает карточки:
            // следующий опрос повторит попытку
            if (!quiet) {
                telegramCard.renderUnavailable();
                emailCard.renderUnavailable();
            }
            return;
        }
        telegramCard.render(channels.get("TELEGRAM"));
        emailCard.render(channels.get("EMAIL"));
        updatePolling();
    }

    private void updatePolling() {
        boolean needed = telegramCard.isWaiting() || emailCard.isWaiting();
        getUI().ifPresent(ui -> ui.setPollInterval(needed ? POLL_INTERVAL_MS : -1));
    }
}
