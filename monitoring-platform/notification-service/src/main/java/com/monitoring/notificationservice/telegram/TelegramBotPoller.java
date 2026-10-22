package com.monitoring.notificationservice.telegram;

import com.monitoring.notificationservice.telegram.TelegramModels.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Получение сообщений бота через long polling (getUpdates), а не webhook:
 * webhook требует публичного HTTPS-адреса, а мы работаем на localhost.
 *
 * Ограничение Telegram: с одним токеном может работать только ОДИН
 * потребитель getUpdates. Второй запущенный экземпляр сервиса (или бот,
 * у которого настроен webhook) получит ошибку 409 Conflict.
 *
 * SmartLifecycle запускает цикл после полной инициализации контекста (БД и
 * Liquibase уже готовы) и останавливает его при завершении приложения.
 */
@Component
public class TelegramBotPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotPoller.class);

    private static final int POLL_TIMEOUT_SECONDS = 30;
    private static final long ERROR_BACKOFF_MS = 5_000;

    private final TelegramApiClient api;
    private final TelegramUpdateHandler handler;

    private volatile boolean running;
    private Thread thread;

    public TelegramBotPoller(TelegramApiClient api, TelegramUpdateHandler handler) {
        this.api = api;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (!api.isConfigured()) {
            // Сервис должен подниматься и без токена: остальные функции
            // (Kafka-консьюмер, REST API) от бота не зависят.
            log.warn("TELEGRAM_BOT_TOKEN не задан: приём сообщений бота отключён");
            return;
        }
        running = true;
        thread = new Thread(this::pollLoop, "telegram-poller");
        thread.setDaemon(true);
        thread.start();
        log.info("Telegram-бот запущен (long polling)");
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        // offset хранится в памяти. После перезапуска Telegram повторно
        // отдаст последнее неподтверждённое обновление; для /start это
        // безвредно: токен уже использован, бот просто ответит, что
        // ссылка недействительна.
        long offset = 0;

        while (running) {
            try {
                List<Update> updates = api.getUpdates(offset, POLL_TIMEOUT_SECONDS);
                for (Update update : updates) {
                    // Сдвигаем offset ДО обработки: сбойное обновление
                    // не должно приходить снова и блокировать очередь.
                    offset = update.updateId() + 1;
                    try {
                        handler.handle(update);
                    } catch (Exception e) {
                        log.error("Не удалось обработать update {}: {}", update.updateId(), e.toString());
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("Сбой long polling Telegram: {}", e.getMessage());
                sleepQuietly(ERROR_BACKOFF_MS);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
