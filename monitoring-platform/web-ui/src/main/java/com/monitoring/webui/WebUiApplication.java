package com.monitoring.webui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// Кастомную тему (frontend/themes/...) добавим отдельным шагом, когда
// понадобится реальная кастомизация — без папки темы на диске @Theme(...)
// уронит старт dev-сервера. Пока используется дефолтная тема Vaadin (Lumo).
public class WebUiApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(WebUiApplication.class, args);
    }
}
