package com.monitoring.coreapi.exception;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> errors
) {

    /** Нарушение валидации конкретного поля. Пустой список для ошибок не про поля. */
    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path,
                              List<FieldViolation> errors) {
        return new ApiError(Instant.now(), status, error, message, path, errors);
    }
}