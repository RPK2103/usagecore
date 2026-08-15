package io.usagecore.entitlementruntime.adapters.inbound.http.error;

import io.usagecore.entitlementruntime.application.security.AuthorizationDeniedException;
import io.usagecore.entitlementruntime.domain.CommercialInvariantException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "io.usagecore.entitlementruntime.adapters.inbound.http")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(CommercialInvariantException.class)
    public ResponseEntity<ApiError> handleInvariant(
            CommercialInvariantException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCodes.INTERNAL_ERROR,
                "Commercial state invariant violation",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), exception);
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
