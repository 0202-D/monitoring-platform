package com.monitoring.coreapi.service;

import com.monitoring.coreapi.entity.Monitor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Нормализация и проверка адреса монитора. Сервер — источник истины:
 * клиентская проверка в web-ui нужна только для быстрой обратной связи,
 * а прямой вызов API (curl, другой клиент) обходит любой интерфейс.
 */
public final class MonitorTargetValidator {

    private static final int MAX_LENGTH = 2048;
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_TXT_LENGTH = 255;

    private static final Set<String> DNS_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "NS", "TXT");
    // Метка доменного имени. Подчёркивание допустимо: оно встречается в _dmarc, _domainkey и т.п.
    private static final Pattern DNS_LABEL = Pattern.compile("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$");
    private static final Pattern IPV4 = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private MonitorTargetValidator() {
    }

    /** Возвращает адрес в каноническом виде или бросает ответ 400. */
    public static String normalizeAndValidate(Monitor.MonitorType type, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw badRequest("Адрес монитора не задан");
        }
        String value = raw.trim();

        if (value.length() > MAX_LENGTH) {
            throw badRequest("Адрес слишком длинный (максимум " + MAX_LENGTH + " символов)");
        }

        return switch (type) {
            case HTTP -> {
                rejectWhitespace(value);
                validateHttp(value);
                yield value;
            }
            case SSL -> {
                rejectWhitespace(value);
                validateSsl(value);
                yield value;
            }
            // У DNS-цели пробелы значимы: "домен тип ожидаемое значение"
            case DNS -> normalizeDns(value);
        };
    }

    private static void rejectWhitespace(String value) {
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw badRequest("Адрес не должен содержать пробелов");
        }
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

    /**
     * DNS-цель: "домен [тип [ожидаемое значение]]", как в dig. Приводится к каноническому виду:
     * домен в нижнем регистре без завершающей точки, тип в верхнем регистре, лишние пробелы
     * между частями убраны (внутри ожидаемого значения они сохраняются).
     */
    private static String normalizeDns(String value) {
        String[] parts = value.split("\\s+", 3);

        String host = normalizeHost(parts[0]);
        if (parts.length == 1) {
            return host;
        }

        String type = parts[1].toUpperCase(Locale.ROOT);
        if (!DNS_TYPES.contains(type)) {
            throw badRequest("Тип DNS-записи должен быть одним из: A, AAAA, CNAME, MX, NS, TXT");
        }
        if (parts.length == 2) {
            return host + " " + type;
        }

        String expected = parts[2].trim();
        validateExpected(type, expected);
        return host + " " + type + " " + expected;
    }

    private static String normalizeHost(String raw) {
        String host = raw.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH || !host.contains(".")) {
            throw badRequest("Укажите доменное имя, например example.com");
        }
        // -1: пустые метки (example..com) сохраняются и не проходят проверку
        for (String label : host.split("\\.", -1)) {
            if (!DNS_LABEL.matcher(label).matches()) {
                throw badRequest("Некорректное доменное имя");
            }
        }
        return host;
    }

    private static void validateExpected(String type, String expected) {
        switch (type) {
            case "A" -> {
                if (!IPV4.matcher(expected).matches()) {
                    throw badRequest("Для записи A ожидается IPv4-адрес, например 93.184.216.34");
                }
            }
            case "AAAA" -> {
                if (!isIpv6Literal(expected)) {
                    throw badRequest("Для записи AAAA ожидается IPv6-адрес, например 2606:2800:220:1::1");
                }
            }
            case "CNAME", "NS" -> {
                if (expected.chars().anyMatch(Character::isWhitespace)) {
                    throw badRequest("Ожидаемое значение должно быть одним доменным именем");
                }
                normalizeHost(expected);
            }
            case "MX" -> {
                // "mail.example.com" или "10 mail.example.com"
                String[] tokens = expected.split("\\s+");
                if (tokens.length > 2 || (tokens.length == 2 && !tokens[0].matches("\\d{1,5}"))) {
                    throw badRequest("Для записи MX ожидается имя сервера, при желании с приоритетом: 10 mail.example.com");
                }
                normalizeHost(tokens[tokens.length - 1]);
            }
            case "TXT" -> {
                if (expected.length() > MAX_TXT_LENGTH) {
                    throw badRequest("Ожидаемый текст слишком длинный (максимум " + MAX_TXT_LENGTH + " символов)");
                }
            }
            default -> throw badRequest("Неподдерживаемый тип записи");
        }
    }

    /**
     * Строгая проверка синтаксиса IPv6 без обращения к сети: отвергает ":::", "1:2:3", "1::2::3".
     * Проверка только по набору символов пропускала такие значения. InetAddress.getByName для
     * этого не годится: для строки, которая выглядит как адрес, но им не является, JDK пробует
     * разрешить её как имя хоста и может обратиться к DNS, то есть заблокировать поток запроса.
     * Java URI умеет строго разбирать IPv6-литерал в квадратных скобках.
     */
    private static boolean isIpv6Literal(String value) {
        if (!value.contains(":") || !IPV6_CHARS.matcher(value).matches()) {
            return false;
        }
        try {
            new URI("http://[" + value + "]/");
            return true;
        } catch (URISyntaxException e) {
            return false;
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
