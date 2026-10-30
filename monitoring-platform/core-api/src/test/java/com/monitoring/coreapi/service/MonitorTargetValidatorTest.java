package com.monitoring.coreapi.service;

import com.monitoring.coreapi.entity.Monitor.MonitorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitorTargetValidatorTest {

    @Test
    void trimsSurroundingWhitespace() {
        // Именно этот случай мы получили в реальности: пробел перед http://
        String result = MonitorTargetValidator.normalizeAndValidate(MonitorType.HTTP, " http://localhost:8091 ");

        assertThat(result).isEqualTo("http://localhost:8091");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com",
        "http://localhost:8091",
        "HTTPS://EXAMPLE.COM/path",
        "https://habr.com/ru/feed/?page=2",
        // "://" внутри query-параметра — не опечатка со схемой
        "https://example.com/?next=http://other.example"
    })
    void acceptsValidHttpUrls(String url) {
        assertThat(MonitorTargetValidator.normalizeAndValidate(MonitorType.HTTP, url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "localhost:8083/monitors",              // нет схемы
        "ftp://example.com",                    // не http/https
        "https://http://localhost:8083/monitors", // схема указана дважды
        "http://",                              // нет хоста
        "example.com",                          // нет схемы
        "https://exa mple.com"                  // пробел внутри
    })
    void rejectsInvalidHttpUrls(String url) {
        assertBadRequest(() -> MonitorTargetValidator.normalizeAndValidate(MonitorType.HTTP, url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankAddress(String url) {
        assertBadRequest(() -> MonitorTargetValidator.normalizeAndValidate(MonitorType.HTTP, url));
    }

    @Test
    void rejectsNullAddress() {
        assertBadRequest(() -> MonitorTargetValidator.normalizeAndValidate(MonitorType.HTTP, null));
    }

    @Test
    void rejectsTooLongAddress() {
        String tooLong = "https://example.com/" + "a".repeat(2100);

        assertBadRequest(() -> MonitorTargetValidator.normalizeAndValidate(MonitorType.HTTP, tooLong));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "https://example.com", "example.com/path"})
    void acceptsValidSslTargets(String target) {
        assertThat(MonitorTargetValidator.normalizeAndValidate(MonitorType.SSL, target)).isEqualTo(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://", "/path"})
    void rejectsSslTargetsWithoutHost(String target) {
        assertBadRequest(() -> MonitorTargetValidator.normalizeAndValidate(MonitorType.SSL, target));
    }

    private void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
