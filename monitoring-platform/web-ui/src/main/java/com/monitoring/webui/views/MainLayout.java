package com.monitoring.webui.views;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;

public class MainLayout extends AppLayout {

    public MainLayout() {
        H1 title = new H1("Monitoring Platform");
        title.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.Margin.MEDIUM);

        Anchor logout = new Anchor("/logout", "Выйти");
        logout.addClassNames(LumoUtility.Margin.MEDIUM);
        // /logout обрабатывает Spring Security, а не Vaadin-роутер: без
        // router-ignore клик перехватывается как SPA-навигация
        // (та же причина, что и у ссылки на вход через Keycloak).
        logout.getElement().setAttribute("router-ignore", true);

        HorizontalLayout header = new HorizontalLayout(new DrawerToggle(), title, logout);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);

        addToNavbar(header);

        VerticalLayout navigation = new VerticalLayout(
            new RouterLink("Мониторы", MonitorsView.class),
            new RouterLink("Настройки", SettingsView.class)
        );
        navigation.setPadding(true);
        addToDrawer(navigation);
    }
}
