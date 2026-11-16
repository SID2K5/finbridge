package com.finbridge.common;

import com.finbridge.logging.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;

/**
 * Error envelope used by every endpoint:
 * { "error": { "code", "message", "fieldErrors" }, "correlationId", "timestamp" }.
 */
public record ApiError(ErrorBody error, String correlationId, Instant timestamp) {

    public static ApiError of(String code, String message) {
        return of(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors) {
        return new ApiError(new ErrorBody(code, message, fieldErrors), CorrelationIdFilter.currentId(), Instant.now());
    }

    public record ErrorBody(String code, String message, List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String message) {
    }
}
