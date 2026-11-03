package com.monitoring.checkerworker.repository;

import com.monitoring.checkerworker.checks.CheckResult;
import com.monitoring.checkerworker.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorJdbcRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUBJECT = "e754da5a-9fb3-47e2-b1f3-68d6163ecc45";
    private static final String DUE = "now() - interval '1 minute'";
    // как будто claimDueMonitors уже отодвинул следующую проверку на интервал монитора
    private static final String AFTER_CLAIM = "now() + interval '300 seconds'";

    @Autowired
    private MonitorJdbcRepository repository;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, keycloak_subject, email) VALUES (?, ?, ?)",
            userId, SUBJECT, "user@example.com");
    }

    // ---------- claimDueMonitors ----------

    @Test
    void claimsDueMonitorAndPushesNextCheckByItsInterval() {
        UUID id = insertMonitor("UP", 0, DUE, true);

        List<DueMonitor> claimed = repository.claimDueMonitors(10);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).id()).isEqualTo(id);
        // в событиях участвует Keycloak sub, а не внутренний app_user.id
        assertThat(claimed.get(0).userSubject()).isEqualTo(SUBJECT);
        assertThat(nextCheckBetween(id, 290, 310)).isTrue();
    }

    @Test
    void ignoresInactiveAndNotYetDueMonitors() {
        insertMonitor("UP", 0, DUE, false);
        insertMonitor("UP", 0, "now() + interval '1 hour'", true);

        assertThat(repository.claimDueMonitors(10)).isEmpty();
    }

    @Test
    void respectsBatchSizeAndTakesOldestFirst() {
        UUID oldest = insertMonitor("UP", 0, "now() - interval '30 minutes'", true);
        UUID middle = insertMonitor("UP", 0, "now() - interval '20 minutes'", true);
        insertMonitor("UP", 0, "now() - interval '10 minutes'", true);

        List<UUID> claimedIds = repository.claimDueMonitors(2).stream().map(DueMonitor::id).toList();

        assertThat(claimedIds).containsExactly(oldest, middle);
    }

    @Test
    void concurrentClaimersNeverTakeTheSameMonitor() throws Exception {
        for (int i = 0; i < 20; i++) {
            insertMonitor("UP", 0, DUE, true);
        }

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<List<DueMonitor>> claim = () -> {
                startTogether.await();
                return repository.claimDueMonitors(10);
            };
            Future<List<DueMonitor>> first = executor.submit(claim);
            Future<List<DueMonitor>> second = executor.submit(claim);

            List<UUID> allClaimed = Stream
                .concat(first.get(30, TimeUnit.SECONDS).stream(), second.get(30, TimeUnit.SECONDS).stream())
                .map(DueMonitor::id)
                .toList();

            // SKIP LOCKED: два воркера делят очередь без пересечений и без потерь
            assertThat(allClaimed).hasSize(20).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------- recordCheckOutcome: статусы, порог, повторы ----------

    @Test
    void singleFailureKeepsStatusAndSchedulesQuickRetry() {
        UUID id = insertMonitor("UP", 0, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "UP", 0), CheckResult.down("boom"));

        assertThat(statusOf(id)).isEqualTo("UP");
        assertThat(failuresOf(id)).isEqualTo(1);
        // повтор через retry-interval-seconds (15 с), а не через интервал монитора (300 с)
        assertThat(nextCheckBetween(id, 10, 20)).isTrue();
        assertThat(outboxEvents()).isEmpty();
        assertThat(checkResultsOf(id)).isEqualTo(1);
    }

    @Test
    void thirdConsecutiveFailureMarksDownAndWritesOutboxEvent() {
        UUID id = insertMonitor("UP", 2, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "UP", 2), CheckResult.down("ConnectException: refused"));

        assertThat(statusOf(id)).isEqualTo("DOWN");
        assertThat(failuresOf(id)).isEqualTo(3);
        // после подтверждённого DOWN монитор снова проверяется по обычному интервалу
        assertThat(nextCheckBetween(id, 290, 310)).isTrue();

        List<Map<String, Object>> events = outboxEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
            .containsEntry("event_type", "MONITOR_DOWN")
            .containsEntry("aggregate_id", id.toString())
            .containsEntry("user_id", SUBJECT)
            .containsEntry("details", "ConnectException: refused");
    }

    @Test
    void successRecoversMonitorImmediatelyAndWritesUpEvent() {
        UUID id = insertMonitor("DOWN", 5, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "DOWN", 5), CheckResult.up(120, 200));

        assertThat(statusOf(id)).isEqualTo("UP");
        assertThat(failuresOf(id)).isZero();
        assertThat(outboxEvents()).extracting(event -> event.get("event_type")).containsExactly("MONITOR_UP");
    }

    @Test
    void successWhileAlreadyUpResetsFailuresWithoutEvent() {
        UUID id = insertMonitor("UP", 1, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "UP", 1), CheckResult.up(120, 200));

        assertThat(statusOf(id)).isEqualTo("UP");
        assertThat(failuresOf(id)).isZero();
        assertThat(outboxEvents()).isEmpty();
    }

    @Test
    void failuresWhileAlreadyDownDoNotRepeatTheEvent() {
        UUID id = insertMonitor("DOWN", 3, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "DOWN", 3), CheckResult.down("still down"));

        assertThat(statusOf(id)).isEqualTo("DOWN");
        assertThat(failuresOf(id)).isEqualTo(4);
        assertThat(outboxEvents()).isEmpty();
        // упавший сайт не перепроверяется каждые 15 секунд
        assertThat(nextCheckBetween(id, 290, 310)).isTrue();
    }

    @Test
    void newMonitorStaysUnknownUntilFailureIsConfirmed() {
        UUID id = insertMonitor("UNKNOWN", 0, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "UNKNOWN", 0), CheckResult.down("boom"));

        assertThat(statusOf(id)).isEqualTo("UNKNOWN");
        assertThat(outboxEvents()).isEmpty();
    }

    @Test
    void newMonitorBecomesDownAfterThresholdFailures() {
        UUID id = insertMonitor("UNKNOWN", 2, AFTER_CLAIM, true);

        repository.recordCheckOutcome(dueMonitor(id, "UNKNOWN", 2), CheckResult.down("boom"));

        assertThat(statusOf(id)).isEqualTo("DOWN");
        assertThat(outboxEvents()).extracting(event -> event.get("event_type")).containsExactly("MONITOR_DOWN");
    }

    // ---------- вспомогательные методы ----------

    private UUID insertMonitor(String status, int failures, String nextCheckAtSql, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO monitor (id, user_id, type, target_url, check_interval_seconds, is_active, "
                + "current_status, consecutive_failures, next_check_at) "
                + "VALUES (?, ?, 'HTTP', ?, 300, ?, ?, ?, " + nextCheckAtSql + ")",
            id, userId, "https://example.com/" + id, active, status, failures);
        return id;
    }

    private DueMonitor dueMonitor(UUID id, String status, int failures) {
        return new DueMonitor(id, userId, SUBJECT, "HTTP", "https://example.com", 300, status, failures);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT current_status FROM monitor WHERE id = ?", String.class, id);
    }

    private int failuresOf(UUID id) {
        return jdbc.queryForObject("SELECT consecutive_failures FROM monitor WHERE id = ?", Integer.class, id);
    }

    private int checkResultsOf(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM check_result WHERE monitor_id = ?", Integer.class, id);
    }

    private boolean nextCheckBetween(UUID id, int fromSeconds, int toSeconds) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT next_check_at BETWEEN now() + (? * interval '1 second') "
                + "AND now() + (? * interval '1 second') FROM monitor WHERE id = ?",
            Boolean.class, fromSeconds, toSeconds, id));
    }

    private List<Map<String, Object>> outboxEvents() {
        return jdbc.queryForList(
            "SELECT event_type, aggregate_id::text AS aggregate_id, "
                + "payload->>'userId' AS user_id, payload->>'details' AS details "
                + "FROM outbox_event ORDER BY created_at");
    }
}
