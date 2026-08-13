package io.usagecore.usagepipeline.adapters.inbound.http.error;

public final class ApiErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String COMMERCIAL_INVARIANT_VIOLATION = "COMMERCIAL_INVARIANT_VIOLATION";

    private ApiErrorCodes() {
    }
}
