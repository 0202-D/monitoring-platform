package com.monitoring.coreapi.service;

import com.monitoring.coreapi.entity.Monitor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Нормализация и проверка адреса монитора. Сервер — источник истины:
 * клиентская проверка в web-ui нужна только для быстрой обратной связи,
 * а прямой вызов API (curl, другой клиент) обходит любой интерфейс.
 */
public final class MonitorTargetValidator {

    private static final int MAX_LENGTH = 2048;

    private MonitorTargetValidator() {
    }

    /** Возвращает адрес без пробелов по краям или бросает ответ 400. */
    public static String normalizeAndValidate(Monitor.MonitorType type, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw badRequest("Адрес монитора не задан");
        }
        String value = raw.trim();

        if (value.length() > MAX_LENGTH) {
            throw badRequest("Адрес слишком длинный (максимум " + MAX_LENGTH + " символов)");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw badRequest("Адрес не должен содержать пробелов");
        }

        switch (type) {
            case HTTP -> validateHttp(value);
            case SSL -> validateSsl(value);
            case DNS -> {
                // Проверка DNS пока не реализована в checker-worker,
                // поэтому ограничиваемся общими правилами выше.
            }
        }
        return value;
    }

    private static void validateHttp(String value) {
        URI uri = parse(value);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw badRequest("Для HTTP-монитора адрес должен начинаться с http:// или https://");
        }
        if (uri.getHost() == null) {
            throw badRequest("В адресе не найден хост");
        }
        // Путь, начинающийся с "//", почти всегда означает опечатку вида
        // "https://http://example.com" (схема указана дважды)
        if (uri.getRawPath() != null && uri.getRawPath().startsWith("//")) {
            throw badRequest("Адрес выглядит некорректно: проверьте, не указана ли схема дважды");
        }
    }

    private static void validateSsl(String value) {
        // SslChecker принимает и "example.com", и "https://example.com"
        String host = value.contains("://") ? parse(value).getHost() : value.split("/", 2)[0];
        if (host == null || host.isBlank()) {
            throw badRequest("В адресе не найден хост");
        }
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw badRequest("Адрес указан некорректно");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
