package io.usagecore.usagepipeline.adapters.inbound.http.error;

import io.usagecore.usagepipeline.application.quota.CommercialInvariantException;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationConflictException;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationNotFoundException;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.usage.IdempotencyConflictException;
import io.usagecore.usagepipeline.application.usage.UsageAggregateNotFoundException;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");
        return build(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_FAILED, message, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCodes.VALIDATION_FAILED,
                "Request validation failed",
                request
        );
    }

    @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
    public ResponseEntity<ApiError> handleForbidden(RuntimeException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ApiErrorCodes.FORBIDDEN, "Access denied", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleUnauthorized(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.UNAUTHORIZED, ApiErrorCodes.UNAUTHORIZED, "Authentication required", request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.CONFLICT,
                ApiErrorCodes.IDEMPOTENCY_CONFLICT,
                "Idempotency key already used with a different usage payload",
                request
        );
    }

    @ExceptionHandler(CommercialInvariantException.class)
    public ResponseEntity<ApiError> handleCommercialInvariant(
            CommercialInvariantException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCodes.COMMERCIAL_INVARIANT_VIOLATION,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(UsageAggregateNotFoundException.class)
    public ResponseEntity<ApiError> handleAggregateNotFound(
            UsageAggregateNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, ApiErrorCodes.RESOURCE_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ReconciliationNotFoundException.class)
    public ResponseEntity<ApiError> handleReconciliationNotFound(
            ReconciliationNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, ApiErrorCodes.RESOURCE_NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ReconciliationConflictException.class)
    public ResponseEntity<ApiError> handleReconciliationConflict(
            ReconciliationConflictException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.CONFLICT,
                ApiErrorCodes.RECONCILIATION_CONFLICT,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(UsagePublicationException.class)
    public ResponseEntity<ApiError> handlePublicationFailure(
            UsagePublicationException exception,
            HttpServletRequest request
    ) {
        // Not expected on the HTTP ingestion path after Phase 5A (outbox); retained for publisher surfaces.
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.SERVICE_UNAVAILABLE,
                "Usage event publication unavailable",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCodes.INTERNAL_ERROR,
                "An unexpected error occurred",
                request
        );
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String errorCode,
            String message,
            HttpServletRequest request
    ) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        ApiError body = new ApiError(
                Instant.now(clock),
                status.value(),
                errorCode,
                message,
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(status).body(body);
    }
}
