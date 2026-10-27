package com.monitoring.webui.views;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Быстрая проверка адреса монитора в форме, до обращения к серверу.
 * Правила повторяют MonitorTargetValidator из core-api, который остаётся
 * источником истины.
 */
final class TargetUrlValidator {

    private TargetUrlValidator() {
    }

    /** Возвращает текст ошибки или null, если адрес допустим. */
    static String validate(String type, String url) {
        if (url.isEmpty()) {
            return "Укажите URL или хост монитора";
        }
        if (url.chars().anyMatch(Character::isWhitespace)) {
            return "Адрес не должен содержать пробелов";
        }
        if ("HTTP".equals(type)) {
            try {
                URI uri = new URI(url);
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    return "Для HTTP-монитора адрес должен начинаться с http:// или https://";
                }
                if (uri.getHost() == null) {
                    return "В адресе не найден хост";
                }
                if (uri.getRawPath() != null && uri.getRawPath().startsWith("//")) {
                    return "Адрес выглядит некорректно: проверьте, не указана ли схема дважды";
                }
            } catch (URISyntaxException e) {
                return "Адрес указан некорректно";
            }
        }
        return null;
    }
}
