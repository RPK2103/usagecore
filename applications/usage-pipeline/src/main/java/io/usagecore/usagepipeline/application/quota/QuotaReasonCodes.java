package io.usagecore.usagepipeline.application.quota;

/**
 * Deterministic reason codes for {@code POST /api/v1/usage/consume}.
 * Reuses entitlement terminology where commercial meaning aligns.
 */
public final class QuotaReasonCodes {

    public static final String WITHIN_QUOTA = "WITHIN_QUOTA";
    public static final String QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";
    public static final String REQUEST_EXCEEDS_LIMIT = "REQUEST_EXCEEDS_LIMIT";
    public static final String NO_ACTIVE_ENTITLEMENT = "NO_ACTIVE_ENTITLEMENT";
    public static final String ENTITLEMENT_DISABLED = "ENTITLEMENT_DISABLED";
    public static final String ENTITLEMENT_ENABLED = "ENTITLEMENT_ENABLED";
    public static final String UNSUPPORTED_QUOTA_METER_TYPE = "UNSUPPORTED_QUOTA_METER_TYPE";
    public static final String UNKNOWN_METER = "UNKNOWN_METER";
    public static final String METER_NOT_BOUND_TO_FEATURE = "METER_NOT_BOUND_TO_FEATURE";
    /** Strict quota admission closed; late async telemetry may still be accepted while CLOSING. */
    public static final String PERIOD_CLOSING = "PERIOD_CLOSING";
    public static final String PERIOD_RECONCILING = "PERIOD_RECONCILING";
    public static final String PERIOD_FINALIZED = "PERIOD_FINALIZED";

    private QuotaReasonCodes() {
    }
}
