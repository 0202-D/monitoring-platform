package com.monitoring.checkerworker.checks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SslCheckerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void certificateFarFromExpiryIsUp() {
        CheckResult result = SslChecker.evaluateExpiry(NOW.plus(Duration.ofDays(90)), NOW, 120);

        assertThat(result.status()).isEqualTo(CheckResult.Status.UP);
        assertThat(result.responseTimeMs()).isEqualTo(120);
    }

    @Test
    void expiredCertificateIsDown() {
        CheckResult result = SslChecker.evaluateExpiry(NOW.minus(Duration.ofDays(1)), NOW, 120);

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).contains("истёк");
    }

    @Test
    void certificateExpiringSoonIsDown() {
        CheckResult result = SslChecker.evaluateExpiry(NOW.plus(Duration.ofDays(10)), NOW, 120);

        assertThat(result.status()).isEqualTo(CheckResult.Status.DOWN);
        assertThat(result.errorMessage()).contains("истекает").contains("14");
    }

    @Test
    void warningThresholdBoundaryIsInclusiveOfExactlyFourteenDays() {
        // Ровно 14 дней до истечения ещё не считается "скоро": граница фиксируется тестом
        CheckResult exactly = SslChecker.evaluateExpiry(NOW.plus(Duration.ofDays(14)), NOW, 120);
        CheckResult justUnder = SslChecker.evaluateExpiry(
            NOW.plus(Duration.ofDays(14)).minusSeconds(1), NOW, 120);

        assertThat(exactly.status()).isEqualTo(CheckResult.Status.UP);
        assertThat(justUnder.status()).isEqualTo(CheckResult.Status.DOWN);
    }

    @ParameterizedTest
    @CsvSource({
        "example.com,                  example.com",
        "https://example.com,          example.com",
        "https://example.com/path,     example.com",
        "example.com/path,             example.com",
        // порт игнорируется: проверка всегда идёт на 443 (известное ограничение)
        "https://example.com:8443,     example.com"
    })
    void extractsHost(String target, String expectedHost) {
        assertThat(SslChecker.extractHost(target)).isEqualTo(expectedHost);
    }

    @Test
    void invalidAddressGivesDownResultInsteadOfException() {
        // Оба случая отсекаются ДО обращения к сети, поэтому тест не требует интернета
        SslChecker checker = new SslChecker(1);

        assertThat(checker.check("https://").errorMessage()).startsWith("Невалидный адрес");
        assertThat(checker.check("").errorMessage()).startsWith("Не удалось определить хост");
    }
}
