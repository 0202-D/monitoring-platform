package com.monitoring.checkerworker.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

/**
 * Проверка SSL-сертификата напрямую через javax.net.ssl.SSLSocket —
 * никаких внешних библиотек не нужно, стандартный JSSE API полностью
 * покрывает задачу "подключиться и посмотреть на сертификат сервера".
 *
 * targetUrl ожидается в виде "example.com" или "https://example.com" —
 * схему, если есть, отбрасываем, порт всегда 443 (для произвольного
 * порта потребовалось бы расширить формат ввода, не делаем этого сейчас).
 */
@Component
@Slf4j
public class SslChecker implements Checker {

    private static final int HTTPS_PORT = 443;

    // Если сертификат истекает раньше этого порога — тоже считаем DOWN,
    // не дожидаясь фактического истечения. Для пользователя "сертификат
    // истекает через 3 дня" требует действия ровно так же, как "уже истёк".
    private static final Duration EXPIRY_WARNING_THRESHOLD = Duration.ofDays(14);

    private final int timeoutMs;

    public SslChecker(@Value("${checker.http-timeout-seconds:10}") int timeoutSeconds) {
        this.timeoutMs = timeoutSeconds * 1000;
    }

    @Override
    public CheckResult check(String targetUrl) {
        String host = extractHost(targetUrl);
        long startedAt = System.currentTimeMillis();

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(host, HTTPS_PORT), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();

            int responseTimeMs = (int) (System.currentTimeMillis() - startedAt);

            SSLSession session = socket.getSession();
            Certificate[] peerCertificates = session.getPeerCertificates();
            if (peerCertificates.length == 0 || !(peerCertificates[0] instanceof X509Certificate cert)) {
                return CheckResult.down("Сервер не предоставил X.509 сертификат");
            }

            Instant expiresAt = cert.getNotAfter().toInstant();
            Instant now = Instant.now();

            if (expiresAt.isBefore(now)) {
                return CheckResult.down("Сертификат истёк " + expiresAt);
            }
            if (expiresAt.isBefore(now.plus(EXPIRY_WARNING_THRESHOLD))) {
                // Технически сертификат ещё валиден, но мы сознательно
                // трактуем это как DOWN — пользователю нужен алерт ДО
                // реального истечения, а не в момент, когда уже поздно
                // что-то делать. Полноценный раздельный статус "SSL_EXPIRING"
                // (без полного DOWN) — отдельное улучшение на будущее,
                // потребует либо нового значения в Monitor.Status, либо
                // отдельного события без смены статуса.
                return CheckResult.down("Сертификат истекает " + expiresAt
                    + " (менее " + EXPIRY_WARNING_THRESHOLD.toDays() + " дней)");
            }

            return CheckResult.up(responseTimeMs, null);

        } catch (IOException e) {
            log.debug("SSL check failed for {}: {}", host, e.getMessage());
            return CheckResult.down(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error checking SSL for {}", host, e);
            return CheckResult.down("Неожиданная ошибка: " + e.getMessage());
        }
    }

    private String extractHost(String targetUrl) {
        if (targetUrl.contains("://")) {
            return URI.create(targetUrl).getHost();
        }
        // Без схемы URI.create трактует всю строку как host — но на случай
        // "example.com/path" (без схемы, с путём) берём только host-часть.
        String withoutPath = targetUrl.split("/", 2)[0];
        return withoutPath;
    }
}
