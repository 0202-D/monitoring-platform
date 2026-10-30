package com.monitoring.checkerworker.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Проверка SSL-сертификата напрямую через javax.net.ssl.SSLSocket —
 * никаких внешних библиотек не нужно, стандартный JSSE API покрывает задачу.
 *
 * Что проверяется:
 *  - цепочка доверия (по хранилищу доверенных сертификатов JVM);
 *  - соответствие сертификата имени хоста;
 *  - срок действия, с предупреждением заранее (см. EXPIRY_WARNING_THRESHOLD).
 *
 * targetUrl ожидается в виде "example.com" или "https://example.com" —
 * схема, если есть, отбрасывается, порт всегда 443.
 */
@Component
@Slf4j
public class SslChecker implements Checker {

    private static final int HTTPS_PORT = 443;

    // Если сертификат истекает раньше этого порога — тоже считаем DOWN,
    // не дожидаясь фактического истечения: пользователю нужен алерт ДО
    // того, как сайт начнёт показывать ошибку посетителям.
    static final Duration EXPIRY_WARNING_THRESHOLD = Duration.ofDays(14);

    private final int timeoutMs;

    public SslChecker(@Value("${checker.http-timeout-seconds:10}") int timeoutSeconds) {
        this.timeoutMs = timeoutSeconds * 1000;
    }

    @Override
    public CheckResult check(String targetUrl) {
        String host;
        try {
            host = extractHost(targetUrl);
        } catch (IllegalArgumentException e) {
            return CheckResult.down("Невалидный адрес: " + e.getMessage());
        }
        if (host == null || host.isBlank()) {
            return CheckResult.down("Не удалось определить хост из адреса: " + targetUrl);
        }

        long startedAt = System.currentTimeMillis();
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(host, HTTPS_PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            configureTls(socket, host);
            socket.startHandshake();

            int responseTimeMs = (int) (System.currentTimeMillis() - startedAt);

            SSLSession session = socket.getSession();
            Certificate[] peerCertificates = session.getPeerCertificates();
            if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate cert)) {
                return CheckResult.down("Сервер не предоставил X.509 сертификат");
            }

            return evaluateExpiry(cert.getNotAfter().toInstant(), Instant.now(), responseTimeMs);

        } catch (IOException e) {
            // Сюда попадают и сетевые сбои, и ошибки TLS: недоверенный
            // сертификат, несовпадение имени хоста и т.п. (SSLHandshakeException)
            log.debug("SSL check failed for {}: {}", host, e.getMessage());
            return CheckResult.down(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error checking SSL for {}", host, e);
            return CheckResult.down("Неожиданная ошибка: " + e.getMessage());
        }
    }

    /**
     * Без явной настройки JSSE проверяет только цепочку доверия, но НЕ
     * сверяет имя хоста: сертификат, выпущенный для другого домена, но
     * подписанный доверенным центром, был бы принят как "всё в порядке".
     * Алгоритм "HTTPS" включает сверку имени хоста с сертификатом.
     */
    private static void configureTls(SSLSocket socket, String host) {
        SSLParameters parameters = socket.getSSLParameters();
        try {
            // Явный SNI: сервер с несколькими сертификатами выбирает нужный по имени
            parameters.setServerNames(List.of(new SNIHostName(host)));
        } catch (IllegalArgumentException e) {
            // Адрес-IP и прочие недопустимые для SNI имена: SNI не отправляем
        }
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
    }

    /**
     * Чистая функция вынесена отдельно, чтобы логику срока действия можно
     * было проверять юнит-тестами с фиксированными датами, без сети.
     */
    static CheckResult evaluateExpiry(Instant expiresAt, Instant now, int responseTimeMs) {
        if (expiresAt.isBefore(now)) {
            return CheckResult.down("Сертификат истёк " + expiresAt);
        }
        if (expiresAt.isBefore(now.plus(EXPIRY_WARNING_THRESHOLD))) {
            return CheckResult.down("Сертификат истекает " + expiresAt
                    + " (менее " + EXPIRY_WARNING_THRESHOLD.toDays() + " дней)");
        }
        return CheckResult.up(responseTimeMs, null);
    }

    static String extractHost(String targetUrl) {
        if (targetUrl.contains("://")) {
            return URI.create(targetUrl).getHost();
        }
        // Без схемы, возможно с путём ("example.com/path"): берём только хост
        return targetUrl.split("/", 2)[0];
    }
}
