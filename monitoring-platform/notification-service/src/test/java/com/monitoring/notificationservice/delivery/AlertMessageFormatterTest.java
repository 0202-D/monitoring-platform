package com.monitoring.notificationservice.delivery;

import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertMessageFormatterTest {

    // 1_700_000_000_000 мс = 14.11.2023 22:13:20 UTC
    private static final long OCCURRED_AT = 1_700_000_000_000L;

    private final AlertMessageFormatter formatter = new AlertMessageFormatter();

    @Test
    void formatsDownEventWithReasonAndUtcTime() {
        String text = formatter.format(event(EventType.MONITOR_DOWN, "ConnectException"));

        assertThat(text)
            .contains("Монитор недоступен")
            .contains("https://example.com")
            .contains("Причина: ConnectException")
            .contains("Время: 14.11.2023 22:13:20 UTC");
    }

    @Test
    void formatsUpEventWithoutReason() {
        String text = formatter.format(event(EventType.MONITOR_UP, null));

        assertThat(text)
            .contains("Монитор снова доступен")
            .contains("https://example.com")
            .doesNotContain("Причина");
    }

    @Test
    void omitsBlankReason() {
        String text = formatter.format(event(EventType.MONITOR_DOWN, "   "));

        assertThat(text).doesNotContain("Причина");
    }

    @Test
    void truncatesVeryLongReason() {
        String text = formatter.format(event(EventType.MONITOR_DOWN, "x".repeat(1000)));

        // Telegram ограничивает сообщение 4096 символами: причина обрезается заранее
        assertThat(text).contains("…");
        assertThat(text.length()).isLessThan(500);
    }

    @Test
    void formatsUnknownEventTypeGenerically() {
        String text = formatter.format(event(EventType.SSL_EXPIRING, null));

        assertThat(text).contains("SSL_EXPIRING");
    }

    private MonitorStatusEvent event(EventType type, String details) {
        return MonitorStatusEvent.newBuilder()
            .setEventId("event-1")
            .setMonitorId("monitor-1")
            .setUserId("user-1")
            .setEventType(type)
            .setTargetUrl("https://example.com")
            .setOccurredAt(OCCURRED_AT)
            .setDetails(details)
            .build();
    }
}
