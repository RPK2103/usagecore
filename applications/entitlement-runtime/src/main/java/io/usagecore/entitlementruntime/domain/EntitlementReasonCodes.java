package io.usagecore.entitlementruntime.domain;

/**
 * Stable reason codes for entitlement decisions.
 * Commercial denials intentionally use non-leaky codes (no cross-tenant leakage).
 */
public final class EntitlementReasonCodes {

    public static final String ENTITLEMENT_ENABLED = "ENTITLEMENT_ENABLED";
    public static final String ENTITLEMENT_DISABLED = "ENTITLEMENT_DISABLED";
    public static final String ENTITLEMENT_LIMITED = "ENTITLEMENT_LIMITED";
    public static final String REQUEST_EXCEEDS_CONTRACT_LIMIT = "REQUEST_EXCEEDS_CONTRACT_LIMIT";
    public static final String NO_ACTIVE_ENTITLEMENT = "NO_ACTIVE_ENTITLEMENT";

    private EntitlementReasonCodes() {
    }
}
