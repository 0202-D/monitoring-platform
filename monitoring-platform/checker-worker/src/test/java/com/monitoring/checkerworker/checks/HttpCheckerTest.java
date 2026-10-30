package com.monitoring.checkerworker.checks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверки идут против настоящего HTTP-сервера из JDK на свободном порту:
 * без внешней сети и без зависимостей, поэтому тесты быстрые и стабильные.
 */
class HttpCheckerTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200));
        server.createContext("/missing", exchange -> respond(exchange, 404));
        server.createContext("/error", exchange -> respond(exchange, 500));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/ok");
            respond(exchange, 302);
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void reportsUpFor200() {
        CheckResult result = new HttpChecker(5).check(baseUrl + "/ok");

        assertThat(result.status()).isEqualTo(CheckResult.Status.UP);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.responseTimeMs()).isNotNull();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void followsRedirects() {
        CheckResult result = new HttpChecker(5).check(baseUrl + "/redirect");

        assertThat(result.status()).isEqualTo(CheckResult.Status.UP);
        assertThat(result.statusCode()).isEqualTo(200);
    }

    @Test
    void reportsDownFor404() {
        CheckResult result = new HttpChecker(5).check(baseUrl + "/missing");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.errorMessage()).isEqualTo("HTTP 404");
    }

    @Test
    void reportsDownFor500() {
        CheckResult result = new HttpChecker(5).check(baseUrl + "/error");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.statusCode()).isEqualTo(500);
    }

    @Test
    void reportsDownWhenNothingListensOnPort() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        CheckResult result = new HttpChecker(5).check("http://127.0.0.1:" + closedPort);

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void reportsDownOnTimeout() {
        CheckResult result = new HttpChecker(1).check(baseUrl + "/slow");

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void reportsDownForInvalidUrlInsteadOfThrowing() {
        HttpChecker checker = new HttpChecker(5);

        // Пробел в адресе и адрес без схемы: проверка не должна падать исключением
        assertThat(checker.check(" http://example.com").errorMessage()).startsWith("Невалидный URL");
        assertThat(checker.check("localhost:8083/monitors").errorMessage()).startsWith("Невалидный URL");
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
