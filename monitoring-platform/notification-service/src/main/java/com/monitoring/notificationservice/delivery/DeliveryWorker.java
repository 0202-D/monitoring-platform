package com.monitoring.notificationservice.delivery;

import com.monitoring.events.EventType;
import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.delivery.DeliveryRepository.Delivery;
import com.monitoring.notificationservice.tracing.TraceContextCarrier;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Отправляет строки очереди с повторами. Захват пачки (быстрая транзакция) отделён от
 * отправки, которая идёт параллельно на виртуальных потоках: медленный ответ Telegram
 * не задерживает остальные доставки. Параллельность безопасна для порядка: в одну пачку
 * не попадают две строки одного (монитор, канал), см. DeliveryRepository.claimDue.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryRepository deliveryRepository;
    private final UserChannelRepository channelRepository;
    private final Map<String, ChannelSender> sendersByType;
    private final DeliveryMetrics metrics;
    private final ObservationRegistry observationRegistry;
    private final TraceContextCarrier traceContextCarrier;
    private final DeliveryProperties properties;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DeliveryWorker(DeliveryRepository deliveryRepository,
                          UserChannelRepository channelRepository,
                          List<ChannelSender> senders,
                          DeliveryMetrics metrics,
                          ObservationRegistry observationRegistry,
                          TraceContextCarrier traceContextCarrier,
                          DeliveryProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.channelRepository = channelRepository;
        this.sendersByType = senders.stream()
            .collect(Collectors.toMap(ChannelSender::channelType, Function.identity()));
        this.metrics = metrics;
        this.observationRegistry = observationRegistry;
        this.traceContextCarrier = traceContextCarrier;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${delivery.poll-interval-ms:2000}")
    public void deliverDue() {
        // Строки неготовых каналов (например, не задан токен бота) не берём:
        // попытки на них не тратятся, строки просто ждут
        List<String> readyChannels = sendersByType.values().stream()
            .filter(ChannelSender::isConfigured)
            .map(ChannelSender::channelType)
            .toList();

        List<Delivery> due = deliveryRepository.claimDue(
            properties.batchSize(), properties.leaseSeconds(), readyChannels);
        if (due.isEmpty()) {
            return;
        }

        List<Future<?>> futures = due.stream().<Future<?>>map(d -> executor.submit(() -> process(d))).toList();
        // Ждём завершения пачки: следующий опрос начнётся после неё (fixedDelay),
        // и аренда строк не истечёт посреди отправки
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                log.error("Необработанная ошибка доставки: {}", e.getCause().toString());
            }
        }
    }

    void process(Delivery delivery) {
        String channelType = delivery.channelType();
        ChannelSender sender = sendersByType.get(channelType);
        // Адресат берётся на момент отправки, а не постановки в очередь: если пользователь
        // успел выключить канал или привязать другой аккаунт, учитывается актуальное состояние
        Optional<String> destination = channelRepository.findActiveDestination(delivery.userId(), channelType);

        if (sender == null || destination.isEmpty()) {
            deliveryRepository.markCancelled(delivery.id(), "Канал выключен или недоступен на момент отправки");
            metrics.skipped("cancelled");
            return;
        }

        // Получатель с контекстом из БД: span отправки продолжает трассу приёма события
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>(Map::get);
        context.setCarrier(traceContextCarrier.parse(delivery.traceContext()));

        // Ручной span без URL: токен бота входит в адрес запроса к Telegram,
        // и автоматический span записал бы его в Zipkin
        Observation observation = Observation.createNotStarted("alert.send", () -> context, observationRegistry)
            .lowCardinalityKeyValue("channel", channelType.toLowerCase(Locale.ROOT))
            .highCardinalityKeyValue("monitor.id", delivery.monitorId());

        try {
            observation.observe(() -> sender.send(destination.get(), toEvent(delivery)));
        } catch (DeliveryException e) {
            handleFailure(delivery, e);
            return;
        } catch (RuntimeException e) {
            handleFailure(delivery, new DeliveryException(e.toString(), false));
            return;
        }

        deliveryRepository.markSent(delivery.id());
        metrics.delivered(channelType);
        log.info("Событие {} [{}] доставлено пользователю {} в канал {}",
            delivery.eventId(), delivery.eventType(), delivery.userId(), channelType);
    }

    private void handleFailure(Delivery delivery, DeliveryException e) {
        String channelType = delivery.channelType();

        if (e.isPermanent()) {
            // Повторять бессмысленно: помечаем строку и сам канал (пользователь увидит
            // причину и сможет подключить канал заново)
            deliveryRepository.markFailed(delivery.id(), e.getMessage());
            metrics.failed(channelType, "permanent");
            channelRepository.deactivate(delivery.userId(), channelType);
            log.warn("Канал {} пользователя {} помечен сломанным: {}", channelType, delivery.userId(), e.getMessage());
            return;
        }

        metrics.failed(channelType, "transient");
        if (delivery.attempts() >= properties.maxAttempts()) {
            deliveryRepository.markFailed(delivery.id(), e.getMessage());
            metrics.undelivered(channelType);
            log.error("Уведомление по событию {} не доставлено после {} попыток: {}",
                delivery.eventId(), delivery.attempts(), e.getMessage());
            return;
        }

        long delay = backoffSeconds(delivery.attempts(), properties);
        deliveryRepository.scheduleRetry(delivery.id(), delay, e.getMessage());
        log.warn("Сбой доставки события {} (попытка {}), повтор через {} с: {}",
            delivery.eventId(), delivery.attempts(), delay, e.getMessage());
    }

    /** Экспоненциальная пауза: initial, 2*initial, 4*initial ... не больше максимума. */
    static long backoffSeconds(int attempts, DeliveryProperties properties) {
        long delay = properties.initialBackoffSeconds() * (1L << Math.min(attempts - 1, 20));
        return Math.min(delay, properties.maxBackoffSeconds());
    }

    private MonitorStatusEvent toEvent(Delivery delivery) {
        return MonitorStatusEvent.newBuilder()
            .setEventId(delivery.eventId())
            .setMonitorId(delivery.monitorId())
            .setUserId(delivery.userId().toString())
            .setEventType(EventType.valueOf(delivery.eventType()))
            .setTargetUrl(delivery.targetUrl())
            .setOccurredAt(delivery.occurredAt().toEpochMilli())
            .setDetails(delivery.details())
            .build();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
