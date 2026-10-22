package com.monitoring.checkerworker.checks;

public record CheckResult(
    Status status,
    Integer responseTimeMs,  // null, если проверка не дошла до получения ответа
    Integer statusCode,       // null для SSL/DNS-проверок, где нет HTTP-кода
    String errorMessage       // null при успехе
) {
    public enum Status {
        UP, DOWN
    }

    public static CheckResult up(int responseTimeMs, Integer statusCode) {
        return new CheckResult(Status.UP, responseTimeMs, statusCode, null);
    }

    public static CheckResult down(String errorMessage) {
        return new CheckResult(Status.DOWN, null, null, errorMessage);
    }

    public static CheckResult down(Integer statusCode, String errorMessage) {
        return new CheckResult(Status.DOWN, null, statusCode, errorMessage);
    }
}
