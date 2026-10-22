package com.monitoring.checkerworker.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * java.net.http.HttpClient — не Apache HttpClient/OkHttp. Стандартный
 * JDK-клиент, появившийся в Java 11, здесь выбран сознательно: он
 * неблокирующий внутри и прекрасно сочетается с virtual threads (не
 * удерживает платформенный поток на I/O-ожидании), и не добавляет
 * внешнюю зависимость там, где стандартная библиотека полностью
 * покрывает потребность.
 */
@Component
@Slf4j
public class HttpChecker implements Checker {

    private final HttpClient httpClient;
    private final Duration timeout;

    public HttpChecker(@Value("${checker.http-timeout-seconds:10}") int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public CheckResult check(String targetUrl) {
        long startedAt = System.currentTimeMillis();

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(timeout)
                // HEAD было бы дешевле, но не все серверы его корректно
                // поддерживают (некоторые отвечают 405 на HEAD, хотя GET
                // работает нормально) — GET надёжнее для разнородных целей,
                // которые заранее не проверишь на HEAD-совместимость.
                .GET()
                .build();
        } catch (IllegalArgumentException e) {
            // Невалидный URL в targetUrl — ошибка конфигурации монитора,
            // а не сетевая проблема, но для пользователя результат тот же:
            // монитор недоступен для проверки.
            return CheckResult.down("Невалидный URL: " + e.getMessage());
        }

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int responseTimeMs = (int) (System.currentTimeMillis() - startedAt);
            int statusCode = response.statusCode();

            // 2xx-3xx считаем UP. 3xx (редиректы) — тоже успех: сервер
            // ответил и явно управляет трафиком, это не то же самое,
            // что "сайт не отвечает". followRedirects уже их обработал,
            // но финальный код всё равно может быть 3xx в редких случаях
            // (например, redirect loop limit) — трактуем так же мягко.
            if (statusCode >= 200 && statusCode < 400) {
                return CheckResult.up(responseTimeMs, statusCode);
            }
            return CheckResult.down(statusCode, "HTTP " + statusCode);

        } catch (IOException e) {
            // Connection refused, DNS resolution failure, TLS handshake
            // failure и подобное — сеть недоступна или сервер не отвечает
            log.debug("HTTP check failed for {}: {}", targetUrl, e.getMessage());
            return CheckResult.down(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.down("Проверка прервана");
        } catch (Exception e) {
            // Defensive catch-all: таймаут (HttpTimeoutException — подкласс
            // IOException, уже покрыт выше) и прочие неожиданные случаи
            // не должны ронять весь воркер из-за одного плохого URL.
            log.warn("Unexpected error checking {}", targetUrl, e);
            return CheckResult.down("Неожиданная ошибка: " + e.getMessage());
        }
    }
}
