package com.kairostek.cms.web;

import com.kairostek.cms.dto.ErrorResponse;
import com.kairostek.cms.exception.ConcurrentUpdateException;
import com.kairostek.cms.exception.ContentNotFoundException;
import com.kairostek.cms.exception.DuplicateContentKeyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates all application exceptions into a consistent {@link ErrorResponse}
 * envelope with appropriate HTTP status codes.
 *
 * <p>Note: Redis failures never reach this handler — the cache layer degrades
 * silently so the service keeps working when Redis is unavailable. A genuine
 * Redis outage therefore results in normal 200 responses (database-only), not
 * errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ContentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ContentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateContentKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateContentKeyException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_CONTENT_KEY", ex.getMessage(), request);
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(ConcurrentUpdateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", ex.getMessage(), request);
    }

    /** Bean validation failures (e.g. blank title). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Validation failed", details, request);
    }

    /** Malformed JSON body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String message = ex.getMostSpecificCause() instanceof IllegalArgumentException cause
                ? cause.getMessage()
                : "Malformed or unreadable request body";
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
    }

    /** Invalid enum value in a query param or path (e.g. status=UNKNOWN). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = ex.getRequiredType() != null && ex.getRequiredType().isEnum()
                ? "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'"
                : "Invalid value for parameter '" + ex.getName() + "'";
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), request);
    }

    /** Database errors: log full detail server-side, return a safe message. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDatabase(DataAccessException ex, HttpServletRequest request) {
        log.error("Database error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR", "A database error occurred", request);
    }

    /** Catch-all for anything unexpected. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        return build(status, code, message, List.of(), request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                List<ErrorResponse.FieldError> details, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, message, details, request.getRequestURI()));
    }
}
