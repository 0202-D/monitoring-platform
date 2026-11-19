package com.monitoring.notificationservice.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Счётчики доставки с тегом канала. Регистрируются заранее, с нулём: иначе ряд появляется
 * только после первого события, и Prometheus может не засчитать самое первое увеличение.
 *   alerts.planned{channel}       — строка поставлена в очередь
 *   alerts.delivered{channel}     — доставлено
 *   alerts.failed{channel,kind}   — неудачная ПОПЫТКА (transient — будет повтор, permanent — канал сломан)
 *   alerts.undelivered{channel}   — повторы исчерпаны, уведомление НЕ доставлено
 *   alerts.skipped{reason}        — доставка не понадобилась или отменена
 */
@Component
public class DeliveryMetrics {

    private static final List<String> SKIP_REASONS = List.of(
        "no_channel", "channel_disabled", "channel_broken", "duplicate", "invalid_user_id", "cancelled");

    private final MeterRegistry registry;

    public DeliveryMetrics(MeterRegistry registry, List<ChannelSender> senders) {
        this.registry = registry;
        for (String reason : SKIP_REASONS) {
            Counter.builder("alerts.skipped").tag("reason", reason).register(registry);
        }
        for (ChannelSender sender : senders) {
            String channel = tag(sender.channelType());
            Counter.builder("alerts.planned").tag("channel", channel).register(registry);
            Counter.builder("alerts.delivered").tag("channel", channel).register(registry);
            Counter.builder("alerts.undelivered").tag("channel", channel).register(registry);
            for (String kind : List.of("transient", "permanent")) {
                Counter.builder("alerts.failed").tag("channel", channel).tag("kind", kind).register(registry);
            }
        }
    }

    void planned(String channelType) {
        registry.counter("alerts.planned", "channel", tag(channelType)).increment();
    }

    void delivered(String channelType) {
        registry.counter("alerts.delivered", "channel", tag(channelType)).increment();
    }

    void failed(String channelType, String kind) {
        registry.counter("alerts.failed", "channel", tag(channelType), "kind", kind).increment();
    }

    void undelivered(String channelType) {
        registry.counter("alerts.undelivered", "channel", tag(channelType)).increment();
    }

    void skipped(String reason) {
        registry.counter("alerts.skipped", "reason", reason).increment();
    }

    private static String tag(String channelType) {
        return channelType.toLowerCase(Locale.ROOT);
    }
}
