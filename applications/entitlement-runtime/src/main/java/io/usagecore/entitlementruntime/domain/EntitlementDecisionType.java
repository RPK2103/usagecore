package io.usagecore.entitlementruntime.domain;

/**
 * Commercial entitlement decision outcomes for authenticated checks.
 */
public enum EntitlementDecisionType {
    ALLOW,
    DENY,
    ALLOW_WITH_LIMIT
}
