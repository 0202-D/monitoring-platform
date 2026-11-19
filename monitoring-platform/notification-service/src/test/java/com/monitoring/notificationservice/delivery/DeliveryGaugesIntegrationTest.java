package com.monitoring.notificationservice.delivery;

import com.monitoring.notificationservice.delivery.DeliveryRepository.NewDelivery;
import com.monitoring.notificationservice.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет, что SQL метрик очереди выполняется на настоящей схеме и даёт верные числа. */
class DeliveryGaugesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DeliveryRepository repository;

    @Test
    void queueGaugesReflectPendingAndFailedRows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DeliveryGauges(jdbc, registry);

        assertThat(gauge(registry, "notification.queue.pending")).isZero();

        repository.insertIfAbsent(delivery("e1", "m1"));
        repository.insertIfAbsent(delivery("e2", "m2"));
        assertThat(gauge(registry, "notification.queue.pending")).isEqualTo(2.0);
        assertThat(gauge(registry, "notification.queue.oldest.pending.age")).isGreaterThanOrEqualTo(0.0);

        UUID id = repository.claimDue(1, 120, List.of("TELEGRAM")).get(0).id();
        repository.markFailed(id, "HTTP 403");

        assertThat(gauge(registry, "notification.queue.pending")).isEqualTo(1.0);
        assertThat(gauge(registry, "notification.queue.failed.recent")).isEqualTo(1.0);
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private NewDelivery delivery(String eventId, String monitorId) {
        return new NewDelivery(eventId, "TELEGRAM", UUID.randomUUID(), monitorId, "MONITOR_DOWN",
            "https://example.com", null, Instant.parse("2026-01-01T00:00:00Z"), null);
    }
}
