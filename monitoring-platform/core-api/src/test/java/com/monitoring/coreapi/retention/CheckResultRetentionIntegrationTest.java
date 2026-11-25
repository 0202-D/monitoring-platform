package com.monitoring.coreapi.retention;

import com.monitoring.coreapi.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckResultRetentionIntegrationTest extends AbstractPostgresIntegrationTest {

    private UUID monitorId;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void createMonitor() {
        registry = new SimpleMeterRegistry();
        UUID userId = UUID.randomUUID();
        monitorId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, keycloak_subject, email) VALUES (?, ?, ?)",
            userId, "sub-" + userId, "user@example.com");
        jdbc.update(
            "INSERT INTO monitor (id, user_id, type, target_url, check_interval_seconds, is_active, "
                + "current_status, consecutive_failures, next_check_at) "
                + "VALUES (?, ?, 'HTTP', 'https://example.com', 300, true, 'UP', 0, now())",
            monitorId, userId);
    }

    @Test
    void deletesOnlyChecksOlderThanRetention() {
        insertCheck(5);
        insertCheck(29);
        insertCheck(31);
        insertCheck(90);

        long deleted = job(30, 10_000).purgeOlderThanDays(30);

        assertThat(deleted).isEqualTo(2);
        assertThat(remainingAgesInDays()).hasSize(2);
        assertThat(oldestRemainingAgeDays()).isLessThan(30);
    }

    @Test
    void deletesInSeveralBatchesUntilNothingOldRemains() {
        for (int i = 0; i < 10; i++) {
            insertCheck(40 + i);
        }
        insertCheck(1);
        insertCheck(2);

        // Пачка в 3 строки: 10 старых строк удаляются за четыре захода (3 + 3 + 3 + 1)
        long deleted = job(30, 3).purgeOlderThanDays(30);

        assertThat(deleted).isEqualTo(10);
        assertThat(totalChecks()).isEqualTo(2);
    }

    @Test
    void doesNothingWhenNothingIsOld() {
        insertCheck(1);
        insertCheck(10);

        assertThat(job(30, 10_000).purgeOlderThanDays(30)).isZero();
        assertThat(totalChecks()).isEqualTo(2);
    }

    @Test
    void countsDeletedRowsInMetric() {
        insertCheck(40);
        insertCheck(50);
        insertCheck(5);

        job(30, 10_000).purgeOlderThanDays(30);

        assertThat(registry.counter("retention.deleted.rows", "table", "check_result").count()).isEqualTo(2.0);
    }

    @Test
    void scheduledRunDeletesWhenEnabled() {
        insertCheck(40);

        job(30, 10_000).run();

        assertThat(totalChecks()).isZero();
    }

    @Test
    void disabledJobKeepsEverything() {
        insertCheck(400);

        new CheckResultRetentionJob(jdbc, new CheckResultRetentionProperties(false, 30, 10_000), registry).run();

        assertThat(totalChecks()).isEqualTo(1);
    }

    @Test
    void nonPositiveRetentionKeepsEverythingInsteadOfDeletingAll() {
        // Опечатка в настройке (0 дней) не должна стереть всю историю
        insertCheck(1);

        new CheckResultRetentionJob(jdbc, new CheckResultRetentionProperties(true, 0, 10_000), registry).run();

        assertThat(totalChecks()).isEqualTo(1);
    }

    // ---------- вспомогательные методы ----------

    private CheckResultRetentionJob job(int days, int batchSize) {
        return new CheckResultRetentionJob(jdbc, new CheckResultRetentionProperties(true, days, batchSize), registry);
    }

    private void insertCheck(int ageDays) {
        jdbc.update(
            "INSERT INTO check_result (monitor_id, checked_at, status) "
                + "VALUES (?, now() - (? * interval '1 day'), 'UP')",
            monitorId, ageDays);
    }

    private int totalChecks() {
        return jdbc.queryForObject("SELECT count(*) FROM check_result", Integer.class);
    }

    private java.util.List<Integer> remainingAgesInDays() {
        return jdbc.queryForList(
            "SELECT floor(extract(epoch FROM now() - checked_at) / 86400)::int FROM check_result", Integer.class);
    }

    private int oldestRemainingAgeDays() {
        return jdbc.queryForObject(
            "SELECT max(floor(extract(epoch FROM now() - checked_at) / 86400))::int FROM check_result", Integer.class);
    }
}
