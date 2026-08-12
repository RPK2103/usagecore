package io.usagecore.entitlementruntime.domain;

/**
 * Snapshot entitlement mode from an activated ContractVersion (not live PlanFeature).
 */
public enum SnapshotEntitlementMode {
    ENABLED,
    DISABLED,
    LIMITED
}
