package com.monitoring.notificationservice.delivery;

import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.channel.UserChannelRepository.ChannelInfo;
import com.monitoring.notificationservice.delivery.DeliveryRepository.NewDelivery;
import com.monitoring.notificationservice.tracing.TraceContextCarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Принимает событие из Kafka и ставит в очередь по строке на каждый включённый и исправный
 * канал пользователя. Сама отправка — дело DeliveryWorker. Благодаря этому сбой Telegram
 * или почты не блокирует консьюмер Kafka и не задерживает другие события.
 * Идемпотентность: повторная доставка события из Kafka упирается в уникальный
 * (event_id, channel_type) в таблице и не создаёт дубль.
 */
@Service
public class DeliveryPlanner {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPlanner.class);

    private final UserChannelRepository channelRepository;
    private final DeliveryRepository deliveryRepository;
    private final List<ChannelSender> senders;
    private final DeliveryMetrics metrics;
    private final TraceContextCarrier traceContextCarrier;

    public DeliveryPlanner(UserChannelRepository channelRepository,
                           DeliveryRepository deliveryRepository,
                           List<ChannelSender> senders,
                           DeliveryMetrics metrics,
                           TraceContextCarrier traceContextCarrier) {
        this.channelRepository = channelRepository;
        this.deliveryRepository = deliveryRepository;
        this.senders = senders;
        this.metrics = metrics;
        this.traceContextCarrier = traceContextCarrier;
    }

    public void plan(MonitorStatusEvent event) {
        UUID userId;
        try {
            userId = UUID.fromString(event.getUserId());
        } catch (IllegalArgumentException e) {
            // Повторять бессмысленно: событие с таким userId не станет корректным
            metrics.skipped("invalid_user_id");
            log.warn("Событие {} с некорректным userId '{}' пропущено", event.getEventId(), event.getUserId());
            return;
        }

        Map<String, ChannelInfo> channels = channelRepository.findByUserId(userId).stream()
            .collect(Collectors.toMap(ChannelInfo::channelType, Function.identity()));
        // Мы внутри наблюдения приёма из Kafka: сохраняем его контекст в строку очереди,
        // чтобы отправка продолжила ту же трассу
        String traceContext = traceContextCarrier.captureCurrent();

        for (ChannelSender sender : senders) {
            String type = sender.channelType();
            ChannelInfo info = channels.get(type);

            if (info == null) {
                metrics.skipped("no_channel");
                continue;
            }
            if (UserChannelRepository.STATUS_BROKEN.equals(info.status())) {
                metrics.skipped("channel_broken");
                continue;
            }
            if (!UserChannelRepository.STATUS_ACTIVE.equals(info.status())) {
                metrics.skipped("channel_unverified");
                continue;
            }
            if (!info.enabled()) {
                metrics.skipped("channel_disabled");
                continue;
            }

            boolean inserted = deliveryRepository.insertIfAbsent(new NewDelivery(
                event.getEventId(), type, userId, event.getMonitorId(), event.getEventType().name(),
                event.getTargetUrl(), event.getDetails(), event.getOccurredAt(),
                traceContext));

            if (inserted) {
                metrics.planned(type);
            } else {
                metrics.skipped("duplicate");
                log.info("Событие {} уже поставлено в очередь для канала {}, повтор пропущен",
                    event.getEventId(), type);
            }
        }
    }
}
