package io.usagecore.controlplane.adapters.inbound.http.error;

import io.usagecore.controlplane.application.catalogue.DuplicateResourceException;
import io.usagecore.controlplane.application.catalogue.ResourceNotFoundException;
import io.usagecore.controlplane.application.security.AuthorizationDeniedException;
import io.usagecore.controlplane.domain.catalogue.DomainInvariantException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "io.usagecore.controlplane.adapters.inbound.http")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

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

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, ApiErrorCodes.RESOURCE_NOT_FOUND, exception.getMessage(), request);
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

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicate(
            DuplicateResourceException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.CONFLICT, ApiErrorCodes.DUPLICATE_RESOURCE, exception.getMessage(), request);
    }

    @ExceptionHandler(DomainInvariantException.class)
    public ResponseEntity<ApiError> handleDomainInvariant(
            DomainInvariantException exception,
            HttpServletRequest request
    ) {
        String message = exception.getMessage() == null ? "Domain conflict" : exception.getMessage();
        return build(HttpStatus.CONFLICT, classifyDomainConflict(message), message, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        String detail = rootMessage(exception);
        if (containsIgnoreCase(detail, "ex_contract_version_activated_no_overlap")) {
            return build(
                    HttpStatus.CONFLICT,
                    ApiErrorCodes.COMMERCIAL_INTERVAL_CONFLICT,
                    "Activated contract version intervals must not overlap for the same contract",
                    request
            );
        }
        if (containsIgnoreCase(detail, "ex_commercial_period_no_overlap")
                || containsIgnoreCase(detail, "uq_commercial_period_tenant_product_bounds")) {
            return build(
                    HttpStatus.CONFLICT,
                    ApiErrorCodes.COMMERCIAL_INTERVAL_CONFLICT,
                    "Commercial periods must not overlap for the same tenant and product",
                    request
            );
        }
        if (containsIgnoreCase(detail, "uq_") || containsIgnoreCase(detail, "unique")
                || containsIgnoreCase(detail, "duplicate")) {
            return build(
                    HttpStatus.CONFLICT,
                    ApiErrorCodes.DUPLICATE_RESOURCE,
                    "Resource conflicts with an existing unique constraint",
                    request
            );
        }
        return build(
                HttpStatus.CONFLICT,
                ApiErrorCodes.DOMAIN_CONFLICT,
                "Persistence conflict",
                request
        );
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handleStorageUnavailable(Exception exception, HttpServletRequest request) {
        log.error("Durable storage unavailable at {}", request.getRequestURI(), exception);
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.SERVICE_UNAVAILABLE,
                "Durable storage unavailable",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception at {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCodes.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String errorCode,
            String message,
            HttpServletRequest request
    ) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                errorCode,
                message,
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(status).body(body);
    }

    static String classifyDomainConflict(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("overlap")) {
            return ApiErrorCodes.COMMERCIAL_INTERVAL_CONFLICT;
        }
        if (lower.contains("already exists") || lower.contains("duplicate")) {
            return ApiErrorCodes.DUPLICATE_RESOURCE;
        }
        if (lower.contains("cannot be mutated")
                || lower.contains("only draft")
                || lower.contains("cannot be published")
                || lower.contains("cannot be modified")
                || lower.contains("invalid commercial period transition")
                || lower.contains("finalized commercial period is terminal")
                || lower.contains("cannot finalize commercial period while a reconciliation")) {
            return ApiErrorCodes.INVALID_STATE_TRANSITION;
        }
        return ApiErrorCodes.DOMAIN_CONFLICT;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }
}
