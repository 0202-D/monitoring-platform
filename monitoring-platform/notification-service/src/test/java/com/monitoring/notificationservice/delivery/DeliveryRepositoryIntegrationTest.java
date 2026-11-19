package com.monitoring.notificationservice.delivery;

import com.monitoring.notificationservice.delivery.DeliveryRepository.Delivery;
import com.monitoring.notificationservice.delivery.DeliveryRepository.NewDelivery;
import com.monitoring.notificationservice.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("e754da5a-9fb3-47e2-b1f3-68d6163ecc45");
    private static final List<String> TELEGRAM = List.of("TELEGRAM");

    @Autowired
    private DeliveryRepository repository;

    // ---------- идемпотентность ----------

    @Test
    void repeatedEventForSameChannelDoesNotCreateSecondRow() {
        assertThat(repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"))).isTrue();
        // Повторная доставка того же события из Kafka
        assertThat(repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"))).isFalse();

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void sameEventForDifferentChannelsGetsOneRowPerChannel() {
        assertThat(repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"))).isTrue();
        assertThat(repository.insertIfAbsent(delivery("e1", "EMAIL", "m1"))).isTrue();

        assertThat(rowCount()).isEqualTo(2);
    }

    // ---------- захват и аренда ----------

    @Test
    void claimsDueRowAndLeasesIt() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));

        List<Delivery> claimed = repository.claimDue(10, 120, TELEGRAM);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).eventId()).isEqualTo("e1");
        assertThat(claimed.get(0).attempts()).isEqualTo(1);
        assertThat(attemptsOf("e1")).isEqualTo(1);
        assertThat(nextAttemptBetween("e1", 110, 130)).isTrue();
        // Строка в аренде: повторный захват её не видит
        assertThat(repository.claimDue(10, 120, TELEGRAM)).isEmpty();
    }

    @Test
    void rowBecomesAvailableAgainWhenLeaseExpires() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        repository.claimDue(10, 120, TELEGRAM);

        // Воркер упал посреди отправки: аренда истекает
        jdbc.update("UPDATE notification_delivery SET next_attempt_at = now() - interval '1 second'");

        List<Delivery> claimed = repository.claimDue(10, 120, TELEGRAM);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).attempts()).isEqualTo(2);
    }

    @Test
    void ignoresRowsOfOtherChannelsAndRowsNotYetDue() {
        repository.insertIfAbsent(delivery("e1", "EMAIL", "m1"));
        repository.insertIfAbsent(delivery("e2", "TELEGRAM", "m2"));
        jdbc.update("UPDATE notification_delivery SET next_attempt_at = now() + interval '1 hour' WHERE event_id = 'e2'");

        assertThat(repository.claimDue(10, 120, TELEGRAM)).isEmpty();
        assertThat(repository.claimDue(10, 120, List.of())).isEmpty();
    }

    @Test
    void respectsBatchSizeAndTakesOldestFirst() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        repository.insertIfAbsent(delivery("e2", "TELEGRAM", "m2"));
        repository.insertIfAbsent(delivery("e3", "TELEGRAM", "m3"));

        List<String> claimed = repository.claimDue(2, 120, TELEGRAM).stream().map(Delivery::eventId).toList();

        assertThat(claimed).containsExactly("e1", "e2");
    }

    // ---------- порядок для одного монитора ----------

    @Test
    void newerRowWaitsForOlderPendingRowOfSameMonitorAndChannel() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        repository.insertIfAbsent(delivery("e2", "TELEGRAM", "m1"));

        // Обе строки готовы, но берётся только старая: иначе UP мог бы обогнать DOWN
        List<Delivery> first = repository.claimDue(10, 120, TELEGRAM);
        assertThat(first).extracting(Delivery::eventId).containsExactly("e1");

        // Пока старая не завершена (даже будучи в аренде), новая ждёт
        assertThat(repository.claimDue(10, 120, TELEGRAM)).isEmpty();

        repository.markSent(first.get(0).id());
        assertThat(repository.claimDue(10, 120, TELEGRAM)).extracting(Delivery::eventId).containsExactly("e2");
    }

    @Test
    void otherMonitorsAreNotBlockedByAWaitingRow() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        repository.insertIfAbsent(delivery("e2", "TELEGRAM", "m1"));
        repository.insertIfAbsent(delivery("e3", "TELEGRAM", "m2"));
        // e1 взята и ждёт повтора, e2 заблокирована ею, а m2 независим
        repository.claimDue(1, 120, TELEGRAM);

        assertThat(repository.claimDue(10, 120, TELEGRAM)).extracting(Delivery::eventId).containsExactly("e3");
    }

    // ---------- параллельные воркеры ----------

    @Test
    void concurrentWorkersNeverClaimTheSameRow() throws Exception {
        // Разные мониторы, чтобы правило порядка не мешало: проверяется именно SKIP LOCKED
        for (int i = 0; i < 20; i++) {
            repository.insertIfAbsent(delivery("e" + i, "TELEGRAM", "m" + i));
        }

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<List<Delivery>> claim = () -> {
                startTogether.await();
                return repository.claimDue(10, 120, TELEGRAM);
            };
            Future<List<Delivery>> first = executor.submit(claim);
            Future<List<Delivery>> second = executor.submit(claim);

            List<String> allClaimed = Stream
                .concat(first.get(30, TimeUnit.SECONDS).stream(), second.get(30, TimeUnit.SECONDS).stream())
                .map(Delivery::eventId)
                .toList();

            assertThat(allClaimed).hasSize(20).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------- переходы состояний ----------

    @Test
    void markSentFinishesRow() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        UUID id = repository.claimDue(10, 120, TELEGRAM).get(0).id();

        repository.markSent(id);

        assertThat(statusOf("e1")).isEqualTo("SENT");
        assertThat(finished("e1")).isTrue();
    }

    @Test
    void scheduleRetryKeepsRowPendingAndMovesNextAttempt() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        UUID id = repository.claimDue(10, 120, TELEGRAM).get(0).id();

        repository.scheduleRetry(id, 30, "timeout");

        assertThat(statusOf("e1")).isEqualTo("PENDING");
        assertThat(nextAttemptBetween("e1", 25, 35)).isTrue();
        assertThat(lastErrorOf("e1")).isEqualTo("timeout");
    }

    @Test
    void markFailedAndCancelledFinishRowAndStoreReason() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        repository.insertIfAbsent(delivery("e2", "TELEGRAM", "m2"));
        List<Delivery> claimed = repository.claimDue(10, 120, TELEGRAM);

        repository.markFailed(claimed.get(0).id(), "HTTP 403");
        repository.markCancelled(claimed.get(1).id(), "канал выключен");

        assertThat(statusOf("e1")).isEqualTo("FAILED");
        assertThat(lastErrorOf("e1")).isEqualTo("HTTP 403");
        assertThat(statusOf("e2")).isEqualTo("CANCELLED");
        assertThat(finished("e1")).isTrue();
        assertThat(finished("e2")).isTrue();
    }

    @Test
    void veryLongErrorIsTruncatedInsteadOfFailingTheUpdate() {
        repository.insertIfAbsent(delivery("e1", "TELEGRAM", "m1"));
        UUID id = repository.claimDue(10, 120, TELEGRAM).get(0).id();

        repository.markFailed(id, "x".repeat(2000));

        assertThat(lastErrorOf("e1")).hasSize(500);
    }

    // ---------- вспомогательные методы ----------

    private NewDelivery delivery(String eventId, String channel, String monitorId) {
        return new NewDelivery(eventId, channel, USER_ID, monitorId, "MONITOR_DOWN",
            "https://example.com", "boom", Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Integer.class);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject("SELECT status FROM notification_delivery WHERE event_id = ?", String.class, eventId);
    }

    private int attemptsOf(String eventId) {
        return jdbc.queryForObject("SELECT attempts FROM notification_delivery WHERE event_id = ?", Integer.class, eventId);
    }

    private String lastErrorOf(String eventId) {
        return jdbc.queryForObject("SELECT last_error FROM notification_delivery WHERE event_id = ?", String.class, eventId);
    }

    private boolean finished(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT finished_at IS NOT NULL FROM notification_delivery WHERE event_id = ?", Boolean.class, eventId));
    }

    private boolean nextAttemptBetween(String eventId, int fromSeconds, int toSeconds) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT next_attempt_at BETWEEN now() + (? * interval '1 second') AND now() + (? * interval '1 second') "
                + "FROM notification_delivery WHERE event_id = ?",
            Boolean.class, fromSeconds, toSeconds, eventId));
    }
}
