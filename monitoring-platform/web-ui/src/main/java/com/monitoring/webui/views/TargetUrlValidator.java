package com.monitoring.webui.views;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Быстрая проверка адреса монитора в форме, до обращения к серверу.
 * Правила повторяют MonitorTargetValidator из core-api, который остаётся
 * источником истины (здесь они чуть мягче: полную проверку ожидаемых значений делает сервер).
 */
final class TargetUrlValidator {

    private static final Set<String> DNS_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "NS", "TXT");
    private static final Pattern DNS_LABEL = Pattern.compile("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$");
    private static final Pattern IPV4 = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    private TargetUrlValidator() {
    }

    /** Возвращает текст ошибки или null, если адрес допустим. */
    static String validate(String type, String url) {
        if (url.isEmpty()) {
            return "Укажите URL или хост монитора";
        }
        // У DNS-цели пробелы значимы: "домен тип ожидаемое значение"
        if ("DNS".equals(type)) {
            return validateDns(url);
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

    private static String validateDns(String value) {
        String[] parts = value.trim().split("\\s+", 3);

        String host = parts[0].toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
        if (host.isEmpty() || !host.contains(".") || host.length() > 253) {
            return "Укажите доменное имя, например example.com";
        }
        for (String label : host.split("\\.", -1)) {
            if (!DNS_LABEL.matcher(label).matches()) {
                return "Некорректное доменное имя";
            }
        }
        if (parts.length >= 2 && !DNS_TYPES.contains(parts[1].toUpperCase(Locale.ROOT))) {
            return "Тип DNS-записи должен быть одним из: A, AAAA, CNAME, MX, NS, TXT";
        }
        if (parts.length == 3 && "A".equalsIgnoreCase(parts[1]) && !IPV4.matcher(parts[2].trim()).matches()) {
            return "Для записи A ожидается IPv4-адрес, например 93.184.216.34";
        }
        return null;
    }
}
