package com.monitoring.webui.views;

import com.monitoring.webui.client.CoreApiClient;
import com.monitoring.webui.client.dto.CreateMonitorRequestDto;
import com.monitoring.webui.client.dto.MonitorDto;
import com.monitoring.webui.client.dto.UpdateMonitorRequestDto;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Route(value = "monitors", layout = MainLayout.class)
@PageTitle("Мои мониторы — Monitoring Platform")
// Vaadin по умолчанию запрещает доступ КО ВСЕМ роутам без явной аннотации
// (@AnonymousAllowed / @PermitAll / @RolesAllowed) — это fail-closed
// поведение, а не "неаннотированное = требует логин". @PermitAll здесь
// означает "доступно любому аутентифицированному пользователю", в отличие
// от @AnonymousAllowed на RegistrationView, где доступ разрешён и без логина.
@PermitAll
public class MonitorsView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Проверки идут не чаще раза в минуту (минимальный интервал монитора),
    // поэтому опрос раз в 10 секунд даёт задержку отображения в пределах
    // десятка секунд, не создавая заметной нагрузки на core-api.
    private static final int AUTO_REFRESH_INTERVAL_MS = 10_000;

    private final CoreApiClient coreApiClient;
    private final Grid<MonitorDto> grid = new Grid<>();
    private ZoneId browserZone;
    private Registration pollRegistration;

    public MonitorsView(CoreApiClient coreApiClient) {
        this.coreApiClient = coreApiClient;

        setSizeFull();
        setPadding(true);

        add(buildWelcomeMessage());
        add(buildHeader());
        add(buildGrid());

        UI.getCurrent().getPage()
                .executeJs("return Intl.DateTimeFormat().resolvedOptions().timeZone")
                .then(String.class, timezone -> {
                    browserZone = ZoneId.of(timezone);
                    refreshMonitors();
                });
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        // Браузер сам шлёт на сервер лёгкий poll-запрос каждые 10 секунд.
        // Он приходит как обычный HTTP-запрос через цепочку Spring Security,
        // поэтому CoreApiClient видит токен пользователя. Фоновый поток с
        // ui.access(...) так не сработал бы: в нём SecurityContextHolder пуст.
        ui.setPollInterval(AUTO_REFRESH_INTERVAL_MS);
        pollRegistration = ui.addPollListener(e -> refreshMonitorsQuietly());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        // Интервал опроса относится ко всему UI, а не к этому view: если не
        // выключить его при уходе со страницы, опрос продолжится на других.
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    private Paragraph buildWelcomeMessage() {
        // Тот же способ достать пользователя, что и был в HomeView —
        // @AuthenticationPrincipal здесь не работает (Vaadin views не
        // проходят через Spring MVC dispatch), поэтому напрямую из контекста.
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String displayName = "гость";
        if (principal instanceof OidcUser oidcUser) {
            displayName = oidcUser.getFullName() != null ? oidcUser.getFullName() : oidcUser.getEmail();
        }
        return new Paragraph("Добро пожаловать, " + displayName + "!");
    }

    private HorizontalLayout buildHeader() {
        Button addButton = new Button("Добавить монитор");
        addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addButton.addClickListener(e -> openCreateDialog());

        HorizontalLayout header = new HorizontalLayout(addButton);
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.END);
        return header;
    }

    private Grid<MonitorDto> buildGrid() {
        grid.addColumn(MonitorDto::type).setHeader("Тип").setAutoWidth(true);
        grid.addColumn(MonitorDto::targetUrl).setHeader("URL / хост").setAutoWidth(true).setFlexGrow(1);
        grid.addColumn(m -> m.checkIntervalSeconds() + " сек").setHeader("Интервал").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::statusBadge)).setHeader("Статус").setAutoWidth(true);
        grid.addColumn(m -> {
                    if (m.nextCheckAt() == null || browserZone == null) {
                        return "—";
                    }

                    return m.nextCheckAt()
                            .atZoneSameInstant(browserZone)
                            .format(TIME_FORMAT);
                })
                .setHeader("Следующая проверка")
                .setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::actionButtons))
                .setHeader("Действия").setAutoWidth(true).setFlexGrow(0);

        grid.setSizeFull();
        return grid;
    }

    /**
     * Цветной бейдж статуса через готовые Lumo-утилиты (badge theme variants) —
     * не пишем свой CSS, используем то, что уже есть во встроенной теме.
     */
    private Span statusBadge(MonitorDto monitor) {
        Span badge = new Span(monitor.currentStatus());
        badge.getElement().getThemeList().add("badge");
        switch (monitor.currentStatus()) {
            case "UP" -> badge.getElement().getThemeList().add("success");
            case "DOWN" -> badge.getElement().getThemeList().add("error");
            default -> badge.getElement().getThemeList().add("contrast");
        }
        return badge;
    }

    private HorizontalLayout actionButtons(MonitorDto monitor) {
        Button editButton = new Button("Изменить", e -> openEditDialog(monitor));

        Button deleteButton = new Button("Удалить", e -> confirmDelete(monitor));
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout actions = new HorizontalLayout(editButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    private void confirmDelete(MonitorDto monitor) {
        // ConfirmDialog — отдельный компонент (не Notification), специально
        // для деструктивных действий: требует явного клика "Да", случайный
        // клик по строке таблицы или мимо не может случайно удалить монитор.
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить монитор?");
        confirm.setText("Монитор \"" + monitor.targetUrl() + "\" и вся история его проверок будут удалены безвозвратно.");
        confirm.setCancelable(true);
        confirm.setConfirmText("Удалить");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                coreApiClient.deleteMonitor(monitor.id());
                refreshMonitors();
                showSuccess("Монитор удалён");
            } catch (CoreApiClient.CoreApiException ex) {
                showError(ex.getMessage());
            }
        });
        confirm.open();
    }

    private void openEditDialog(MonitorDto monitor) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Редактировать монитор");

        // Тип монитора осознанно НЕ редактируется: это не просто
        // косметическое поле — от него зависит, какой Checker используется
        // в checker-worker (HTTP/SSL), и смена типа задним числом сделала бы
        // неоднозначной существующую историю check_result для этого
        // монитора (строки с разной семантикой status_code/error_message
        // внутри одной истории). Если нужен другой тип — логичнее
        // удалить и создать новый монитор, а не "превращать" старый.
        Span typeLabel = new Span("Тип: " + monitor.type());

        TextField targetUrl = new TextField("URL / хост");
        targetUrl.setValue(monitor.targetUrl());
        targetUrl.setWidthFull();

        IntegerField interval = new IntegerField("Интервал проверки (сек)");
        interval.setValue(monitor.checkIntervalSeconds());
        interval.setMin(60);
        interval.setStep(60);

        Checkbox active = new Checkbox("Активен");
        active.setValue(monitor.active());

        FormLayout form = new FormLayout(typeLabel, targetUrl, interval, active);
        dialog.add(form);

        Button save = new Button("Сохранить", e -> {
            if (targetUrl.isEmpty()) {
                showError("Укажите URL или хост монитора");
                return;
            }
            if (interval.getValue() == null || interval.getValue() < 60) {
                showError("Интервал проверки не может быть меньше 60 секунд");
                return;
            }

            try {
                coreApiClient.updateMonitor(monitor.id(), new UpdateMonitorRequestDto(
                        targetUrl.getValue(), interval.getValue(), active.getValue()));
                dialog.close();
                refreshMonitors();
                showSuccess("Монитор обновлён");
            } catch (CoreApiClient.CoreApiException ex) {
                showError(ex.getMessage());
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickShortcut(Key.ENTER);

        Button cancel = new Button("Отмена", e -> dialog.close());

        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    private void refreshMonitors() {
        try {
            List<MonitorDto> monitors = coreApiClient.listMonitors();
            grid.setItems(monitors);
        } catch (CoreApiClient.CoreApiException e) {
            showError(e.getMessage());
        }
    }

    /**
     * Обновление по таймеру. В отличие от refreshMonitors() не показывает
     * уведомление об ошибке: пока core-api недоступен, пользователь иначе
     * видел бы новое сообщение каждые 10 секунд. Следующий опрос просто
     * повторит попытку.
     */
    private void refreshMonitorsQuietly() {
        try {
            grid.setItems(coreApiClient.listMonitors());
        } catch (CoreApiClient.CoreApiException e) {
            // намеренно игнорируем, см. комментарий выше
        }
    }

    private void openCreateDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Новый монитор");

        ComboBox<String> type = new ComboBox<>("Тип");
        type.setItems("HTTP", "SSL", "DNS");
        type.setValue("HTTP");
        type.setRequiredIndicatorVisible(true);

        TextField targetUrl = new TextField("URL / хост");
        targetUrl.setPlaceholder("https://example.com");
        targetUrl.setRequiredIndicatorVisible(true);
        targetUrl.setWidthFull();

        IntegerField interval = new IntegerField("Интервал проверки (сек)");
        interval.setValue(300);
        interval.setMin(60);
        interval.setStep(60);
        interval.setHelperText("Минимум 60 секунд");

        FormLayout form = new FormLayout(type, targetUrl, interval);
        dialog.add(form);

        Button save = new Button("Создать", e -> {
            if (targetUrl.isEmpty()) {
                showError("Укажите URL или хост монитора");
                return;
            }
            if (interval.getValue() == null || interval.getValue() < 60) {
                showError("Интервал проверки не может быть меньше 60 секунд");
                return;
            }

            try {
                coreApiClient.createMonitor(new CreateMonitorRequestDto(
                        type.getValue(), targetUrl.getValue(), interval.getValue()));
                dialog.close();
                refreshMonitors();
                showSuccess("Монитор создан");
            } catch (CoreApiClient.CoreApiException ex) {
                showError(ex.getMessage());
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.addClickShortcut(Key.ENTER);

        Button cancel = new Button("Отмена", e -> dialog.close());

        dialog.getFooter().add(cancel, save);
        dialog.open();
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
