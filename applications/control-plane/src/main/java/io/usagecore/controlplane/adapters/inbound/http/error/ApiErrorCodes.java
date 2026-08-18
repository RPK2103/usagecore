package io.usagecore.controlplane.adapters.inbound.http.error;

/**
 * Stable control-plane API error codes.
 */
public final class ApiErrorCodes {

    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String DOMAIN_CONFLICT = "DOMAIN_CONFLICT";
    public static final String DUPLICATE_RESOURCE = "DUPLICATE_RESOURCE";
    public static final String INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION";
    public static final String COMMERCIAL_INTERVAL_CONFLICT = "COMMERCIAL_INTERVAL_CONFLICT";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ApiErrorCodes() {
    }
}
