package com.monitoring.checkerworker.checks;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * DNS-проверка: домен существует, у него есть записи нужного типа и, если указано,
 * среди них есть ожидаемое значение (так обнаруживают подмену записи).
 *
 * Сравнение значений:
 *  - A, AAAA: адреса сравниваются как числа (IPv6 записывается по-разному, текст сравнивать нельзя);
 *  - CNAME, NS, MX: имена без учёта регистра и завершающей точки, у MX приоритет не сравнивается;
 *  - TXT: ожидаемый текст должен ВХОДИТЬ в запись, без учёта регистра (см. normalizeTxt).
 */
@Component
public class DnsChecker implements Checker {

    private static final int MAX_SHOWN_RECORDS = 5;

    private final DnsResolver resolver;

    public DnsChecker(DnsResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public CheckResult check(String target) {
        DnsTarget parsed;
        try {
            parsed = DnsTarget.parse(target);
        } catch (IllegalArgumentException e) {
            return CheckResult.down("Некорректная цель DNS-проверки: " + e.getMessage());
        }

        long startedAt = System.currentTimeMillis();
        String typeLabel = parsed.type() == null ? "A/AAAA" : parsed.type();

        List<String> records;
        try {
            records = parsed.type() == null
                ? lookupAddresses(parsed.host())
                : resolver.lookup(parsed.host(), parsed.type());
        } catch (DnsLookupException e) {
            return CheckResult.down(describe(e));
        }
        int responseTimeMs = (int) (System.currentTimeMillis() - startedAt);

        if (records.isEmpty()) {
            return CheckResult.down("Нет записей типа " + typeLabel + " для " + parsed.host());
        }
        if (parsed.expected() != null
            && records.stream().noneMatch(record -> matches(parsed.type(), record, parsed.expected()))) {
            return CheckResult.down("Ожидаемое значение «" + parsed.expected() + "» не найдено среди записей "
                + typeLabel + ": " + preview(records));
        }
        return CheckResult.up(responseTimeMs, null);
    }

    /**
     * "Домен имеет адрес": хватает любой записи A или AAAA. Сначала A: если домена нет вовсе,
     * это видно сразу и AAAA не запрашивается. Сбой запроса AAAA важен, только если больше
     * адресов нет: многие домены не имеют IPv6, и это нормально.
     */
    private List<String> lookupAddresses(String host) {
        List<String> addresses = new ArrayList<>();
        DnsLookupException failure = null;

        try {
            addresses.addAll(resolver.lookup(host, "A"));
        } catch (DnsLookupException e) {
            if (e.kind() == DnsLookupException.Kind.NX_DOMAIN) {
                throw e;
            }
            failure = e;
        }
        try {
            addresses.addAll(resolver.lookup(host, "AAAA"));
        } catch (DnsLookupException e) {
            if (failure == null) {
                failure = e;
            }
        }

        if (addresses.isEmpty() && failure != null) {
            throw failure;
        }
        return addresses;
    }

    private static boolean matches(String type, String record, String expected) {
        return switch (type) {
            case "A", "AAAA" -> {
                String actualAddress = canonicalAddress(record);
                yield actualAddress != null && actualAddress.equals(canonicalAddress(expected));
            }
            case "CNAME", "NS" -> DnsTarget.normalizeName(record).equals(DnsTarget.normalizeName(expected));
            // MX приходит как "10 mail.example.com.": сравниваем только имя сервера
            case "MX" -> DnsTarget.normalizeName(lastToken(record)).equals(DnsTarget.normalizeName(lastToken(expected)));
            case "TXT" -> normalizeTxt(record).toLowerCase(Locale.ROOT)
                .contains(normalizeTxt(expected).toLowerCase(Locale.ROOT));
            default -> false;
        };
    }

    private static String lastToken(String value) {
        String[] tokens = value.trim().split("\\s+");
        return tokens[tokens.length - 1];
    }

    /**
     * JNDI отдаёт строки TXT-записи в кавычках (как минимум содержащие пробелы), а длинная
     * запись из нескольких строк приходит как "часть1" "часть2". По RFC строки одной записи
     * склеиваются БЕЗ разделителя, поэтому стыки убираются раньше кавычек: иначе ожидаемое
     * значение, перекрывающее стык (например, длинный ключ DKIM), не находилось бы.
     */
    private static String normalizeTxt(String value) {
        return value.replace("\" \"", "").replace("\"", "");
    }

    /**
     * Канонический вид IP-адреса или null, если строка не IP-адрес. Без DNS-запросов:
     *  - IPv4 разбирается вручную;
     *  - IPv6 сначала проверяется строгим синтаксическим разбором (через URI), и только затем
     *    передаётся в InetAddress. Нельзя звать InetAddress.getByName для строки, которая
     *    выглядит как адрес, но им не является ("1:2:3"): JDK в этом случае пробует разрешить
     *    её как имя хоста и может обратиться к DNS.
     */
    private static String canonicalAddress(String value) {
        String text = value.trim();
        try {
            if (text.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                String[] octets = text.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) {
                        return null;
                    }
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes).getHostAddress();
            }
            if (isIpv6Literal(text)) {
                return InetAddress.getByName(text).getHostAddress();
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** Строгая проверка синтаксиса IPv6 без обращения к сети: отвергает ":::", "1:2:3", "1::2::3". */
    private static boolean isIpv6Literal(String text) {
        if (text.indexOf(':') < 0 || !text.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            new URI("http://[" + text + "]/");
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String describe(DnsLookupException e) {
        return switch (e.kind()) {
            case NX_DOMAIN -> "Домен не существует (NXDOMAIN)";
            case UNAVAILABLE -> "DNS-сервер не отвечает или вернул ошибку: " + e.getMessage();
            case OTHER -> "Ошибка DNS: " + e.getMessage();
        };
    }

    private static String preview(List<String> records) {
        String shown = records.stream().limit(MAX_SHOWN_RECORDS).collect(Collectors.joining(", "));
        return records.size() > MAX_SHOWN_RECORDS ? shown + " … (всего " + records.size() + ")" : shown;
    }
}
