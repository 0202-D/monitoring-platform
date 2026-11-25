package com.monitoring.notificationservice.delivery;

import com.monitoring.notificationservice.delivery.DeliveryRepository.NewDelivery;
import com.monitoring.notificationservice.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRetentionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");

    @Autowired
    private DeliveryRepository repository;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    void deletesFinishedRowsOlderThanRetentionAndKeepsTheRest() {
        finished("sent-old", "SENT", 31);
        finished("sent-recent", "SENT", 10);
        finished("cancelled-old", "CANCELLED", 31);
        finished("failed-40", "FAILED", 40);    // FAILED хранятся дольше: 90 дней
        finished("failed-100", "FAILED", 100);
        repository.insertIfAbsent(newDelivery("pending"));   // ожидает отправки: не удаляется никогда
        jdbc.update("UPDATE notification_delivery SET created_at = now() - interval '200 days' "
            + "WHERE event_id = 'pending'");

        long deleted = job(new DeliveryRetentionProperties(true, 30, 90, 5000)).purgeDeliveries();

        assertThat(deleted).isEqualTo(3);
        assertThat(remainingEvents()).containsExactlyInAnyOrder("sent-recent", "failed-40", "pending");
    }

    @Test
    void deletesInSeveralBatches() {
        for (int i = 0; i < 7; i++) {
            finished("old-" + i, "SENT", 40 + i);
        }
        finished("fresh", "SENT", 1);

        long deleted = job(new DeliveryRetentionProperties(true, 30, 90, 3)).purgeDeliveries();

        assertThat(deleted).isEqualTo(7);
        assertThat(remainingEvents()).containsExactly("fresh");
    }

    @Test
    void removesTokensExpiredMoreThanADayAgo() {
        for (String table : new String[]{"telegram_link_token", "email_verification_token"}) {
            insertToken(table, "expired-long-ago", "now() - interval '2 days'");
            insertToken(table, "expired-recently", "now() - interval '1 hour'");
            insertToken(table, "still-valid", "now() + interval '1 hour'");
        }

        long deleted = job(new DeliveryRetentionProperties(true, 30, 90, 5000)).purgeExpiredTokens();

        assertThat(deleted).isEqualTo(2);
        for (String table : new String[]{"telegram_link_token", "email_verification_token"}) {
            assertThat(jdbc.queryForList("SELECT token_hash FROM " + table, String.class))
                .containsExactlyInAnyOrder("expired-recently", "still-valid");
        }
    }

    @Test
    void scheduledRunCleansDeliveriesAndTokens() {
        finished("sent-old", "SENT", 40);
        insertToken("telegram_link_token", "expired", "now() - interval '3 days'");

        job(new DeliveryRetentionProperties(true, 30, 90, 5000)).run();

        assertThat(remainingEvents()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_link_token", Integer.class)).isZero();
    }

    @Test
    void disabledJobKeepsEverything() {
        finished("sent-old", "SENT", 400);

        job(new DeliveryRetentionProperties(false, 30, 90, 5000)).run();

        assertThat(remainingEvents()).containsExactly("sent-old");
    }

    @Test
    void countsDeletedRowsInMetric() {
        finished("a", "SENT", 40);
        finished("b", "FAILED", 120);
        finished("c", "SENT", 1);

        job(new DeliveryRetentionProperties(true, 30, 90, 5000)).purgeDeliveries();

        assertThat(registry.counter("retention.deleted.rows", "table", "notification_delivery").count())
            .isEqualTo(2.0);
    }

    // ---------- вспомогательные методы ----------

    private DeliveryRetentionJob job(DeliveryRetentionProperties properties) {
        return new DeliveryRetentionJob(jdbc, properties, registry);
    }

    private void finished(String eventId, String status, int ageDays) {
        repository.insertIfAbsent(newDelivery(eventId));
        jdbc.update("UPDATE notification_delivery SET status = ?, finished_at = now() - (? * interval '1 day') "
            + "WHERE event_id = ?", status, ageDays, eventId);
    }

    private NewDelivery newDelivery(String eventId) {
        return new NewDelivery(eventId, "TELEGRAM", USER_ID, "monitor-" + eventId, "MONITOR_DOWN",
            "https://example.com", null, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private void insertToken(String table, String hash, String expiresAtSql) {
        jdbc.update("INSERT INTO " + table + " (token_hash, user_id, expires_at) VALUES (?, ?, " + expiresAtSql + ")",
            hash, UUID.randomUUID());
    }

    private List<String> remainingEvents() {
        return jdbc.queryForList("SELECT event_id FROM notification_delivery", String.class);
    }
}
