package com.kairostek.cms.dto;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error envelope for every failure response.
 *
 * @param code    stable machine-readable error code (e.g. CONTENT_NOT_FOUND)
 * @param message human-readable description
 * @param details optional field-level validation details
 * @param path    request path that failed
 * @param timestamp when the error occurred
 */
public record ErrorResponse(
        boolean success,
        String code,
        String message,
        List<FieldError> details,
        String path,
        Instant timestamp
) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(false, code, message, List.of(), path, Instant.now());
    }

    public static ErrorResponse of(String code, String message, List<FieldError> details, String path) {
        return new ErrorResponse(false, code, message, details, path, Instant.now());
    }
}
