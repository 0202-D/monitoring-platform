package com.monitoring.checkerworker.checks;

/** Сбой DNS-запроса. Вид сбоя определяет, что скажем пользователю. */
public class DnsLookupException extends RuntimeException {

    public enum Kind {
        /** Домена не существует (ответ NXDOMAIN). */
        NX_DOMAIN,
        /** DNS-сервер не отвечает, превышено время ожидания или он вернул ошибку. */
        UNAVAILABLE,
        OTHER
    }

    private final Kind kind;

    public DnsLookupException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
