package com.monitoring.notificationservice.delivery;

import com.monitoring.events.MonitorStatusEvent;

/**
 * Отправка уведомления через один канал. Новый канал (email и т.д.) — это
 * новая реализация: планировщик и воркер остаются прежними.
 */
public interface ChannelSender {

    /** Тип канала в верхнем регистре, как в user_channel.channel_type. */
    String channelType();

    /**
     * Можно ли сейчас отправлять через этот канал (например, задан ли токен бота).
     * Строки неготовых каналов воркер не берёт, и попытки на них не тратятся.
     */
    boolean isConfigured();

    /** @throws DeliveryException при сбое отправки */
    void send(String destination, MonitorStatusEvent event);
}
