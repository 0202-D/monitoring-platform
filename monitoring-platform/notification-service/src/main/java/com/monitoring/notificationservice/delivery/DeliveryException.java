package com.monitoring.notificationservice.delivery;

/**
 * Сбой отправки через канал. permanent = повторять бессмысленно (бот заблокирован,
 * адрес не существует): воркер не будет ретраить, а пометит канал сломанным.
 */
public class DeliveryException extends RuntimeException {

    private final boolean permanent;

    public DeliveryException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
