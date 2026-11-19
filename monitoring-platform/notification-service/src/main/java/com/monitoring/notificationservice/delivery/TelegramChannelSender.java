package com.monitoring.notificationservice.delivery;

import com.monitoring.events.MonitorStatusEvent;
import com.monitoring.notificationservice.telegram.TelegramApiClient;
import com.monitoring.notificationservice.telegram.TelegramApiException;
import org.springframework.stereotype.Component;

@Component
public class TelegramChannelSender implements ChannelSender {

    private final TelegramApiClient api;
    private final AlertMessageFormatter formatter;

    public TelegramChannelSender(TelegramApiClient api, AlertMessageFormatter formatter) {
        this.api = api;
        this.formatter = formatter;
    }

    @Override
    public String channelType() {
        return "TELEGRAM";
    }

    @Override
    public boolean isConfigured() {
        return api.isConfigured();
    }

    @Override
    public void send(String destination, MonitorStatusEvent event) {
        long chatId;
        try {
            chatId = Long.parseLong(destination);
        } catch (NumberFormatException e) {
            // Испорченный chat_id не исправится повтором
            throw new DeliveryException("Некорректный chat_id канала", true);
        }
        try {
            api.sendMessage(chatId, formatter.format(event));
        } catch (TelegramApiException e) {
            throw new DeliveryException(e.getMessage(), e.isPermanent());
        }
    }
}
