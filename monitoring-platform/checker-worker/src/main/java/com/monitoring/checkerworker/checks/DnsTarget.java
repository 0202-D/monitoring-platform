package com.monitoring.checkerworker.checks;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Цель DNS-монитора: "домен [тип [ожидаемое значение]]", как в команде dig.
 * type == null означает "домен существует и имеет адрес (A или AAAA)".
 */
record DnsTarget(String host, String type, String expected) {

    static final Set<String> SUPPORTED_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "NS", "TXT");

    // Метка доменного имени. Подчёркивание допустимо: оно встречается в _dmarc, _domainkey и т.п.
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$");
    private static final int MAX_HOST_LENGTH = 253;

    static DnsTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("цель не задана");
        }
        // trim() до разбиения: пробелы на концах не превращаются в пустые части.
        // Ожидаемое значение — всё, что осталось после типа, вместе с внутренними пробелами (для TXT)
        String[] parts = raw.trim().split("\\s+", 3);

        String host = normalizeName(parts[0]);
        validateHost(host);

        String type = null;
        if (parts.length >= 2) {
            type = parts[1].toUpperCase(Locale.ROOT);
            if (!SUPPORTED_TYPES.contains(type)) {
                throw new IllegalArgumentException("неподдерживаемый тип записи " + parts[1]);
            }
        }
        // Пустого ожидаемого значения не бывает (третья часть начинается с непробельного символа),
        // но проверка дешёвая и защищает от будущих изменений разбора
        String expected = parts.length == 3 && !parts[2].isBlank() ? parts[2].trim() : null;
        return new DnsTarget(host, type, expected);
    }

    /** Нижний регистр и без завершающей точки: так сравниваются имена. */
    static String normalizeName(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Основная проверка адреса выполняется при создании монитора в core-api. Здесь она
     * повторена, потому что чекер не должен полагаться на то, что в базу попало только
     * проверенное (старые строки, правки напрямую в БД): так вместо ошибки JNDI
     * пользователь увидит понятное сообщение.
     */
    private static void validateHost(String host) {
        if (host.isEmpty()) {
            throw new IllegalArgumentException("не указан домен");
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("слишком длинное доменное имя");
        }
        // -1: пустые метки (example..com) сохраняются и не проходят проверку
        for (String label : host.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("некорректное доменное имя");
            }
        }
    }
}
