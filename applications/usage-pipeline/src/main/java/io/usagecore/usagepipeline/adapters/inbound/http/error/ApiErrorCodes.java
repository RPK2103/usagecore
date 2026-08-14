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
    public static final String RECONCILIATION_CONFLICT = "RECONCILIATION_CONFLICT";
    public static final String ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD = "ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD";
    public static final String ADJUSTMENT_ALREADY_APPLIED = "ADJUSTMENT_ALREADY_APPLIED";
    public static final String RECONCILIATION_RUN_NOT_COMPLETED = "RECONCILIATION_RUN_NOT_COMPLETED";
    public static final String ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION =
            "ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION";

    private ApiErrorCodes() {
    }
}
