package com.monitoring.webui.views.channels;

import com.monitoring.webui.client.NotificationApiClient.NotificationApiException;
import com.monitoring.webui.client.dto.ChannelDto;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/**
 * Общий каркас карточки канала уведомлений: заголовок, бейдж состояния, пояснение,
 * переключатель "получать / не получать" и ряд кнопок. Конкретный канал (Telegram, email)
 * только решает, что показывать в каждом состоянии и какие кнопки нужны.
 * Три независимых факта о канале: привязан ли он, выбор пользователя (переключатель)
 * и состояние системы (исправен или сломан). Карточка получает их одним объектом
 * ChannelDto (или null, если канал не привязан).
 */
public abstract class ChannelCard extends VerticalLayout {

    protected final CardHost host;

    private final Span statusBadge = new Span();
    private final Paragraph statusText = new Paragraph();
    private final Checkbox notifyToggle;
    private final HorizontalLayout actions = new HorizontalLayout();

    protected ChannelCard(CardHost host, String title, String toggleLabel) {
        this.host = host;

        setPadding(true);
        setSpacing(true);
        getStyle()
            .set("border", "1px solid var(--lumo-contrast-20pct)")
            .set("border-radius", "var(--lumo-border-radius-l)");

        statusBadge.getElement().getThemeList().add("badge");
        HorizontalLayout titleRow = new HorizontalLayout(new H3(title), statusBadge);
        titleRow.setAlignItems(Alignment.CENTER);

        notifyToggle = new Checkbox(toggleLabel);
        notifyToggle.setVisible(false);
        // isFromClient: реагируем только на действие пользователя, а не на программную
        // установку значения (иначе откат значения при ошибке вызвал бы повторный запрос)
        notifyToggle.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                handleToggle(event.getValue());
            }
        });

        add(titleRow, statusText, notifyToggle, actions);
    }

    /** Тип канала в верхнем регистре, как в notification-service (TELEGRAM, EMAIL). */
    protected abstract String channelType();

    /** Обновить карточку по состоянию канала; null — канал не привязан. */
    public abstract void render(ChannelDto channel);

    /** Ждёт ли карточка внешнего действия пользователя (тогда страница опрашивает сервер). */
    public boolean isWaiting() {
        return false;
    }

    public void renderUnavailable() {
        setBadge("Нет данных", "contrast");
        setStatusText("Не удалось загрузить состояние. Попробуйте обновить страницу.");
        hideToggle();
        actions.getChildren().forEach(child -> child.setVisible(false));
    }

    private void handleToggle(boolean enabled) {
        try {
            host.api().setChannelEnabled(channelType(), enabled);
            host.refresh();
            host.success(enabled ? "Уведомления включены" : "Уведомления выключены");
        } catch (NotificationApiException e) {
            // Откатываем переключатель: на сервере значение не изменилось
            notifyToggle.setValue(!enabled);
            host.error(e.getMessage());
        }
    }

    protected HorizontalLayout actions() {
        return actions;
    }

    /** Добавляет компонент над рядом кнопок (под переключателем). */
    protected void addAboveActions(Component component) {
        addComponentAtIndex(indexOf(actions), component);
    }

    protected void setBadge(String text, String variant) {
        statusBadge.setText(text);
        statusBadge.getElement().getThemeList().clear();
        statusBadge.getElement().getThemeList().add("badge");
        statusBadge.getElement().getThemeList().add(variant);
    }

    protected void setStatusText(String text) {
        statusText.setText(text);
    }

    protected void showToggle(boolean value) {
        notifyToggle.setVisible(true);
        // программная установка: слушатель её игнорирует (isFromClient = false)
        notifyToggle.setValue(value);
    }

    protected void hideToggle() {
        notifyToggle.setVisible(false);
    }
}
