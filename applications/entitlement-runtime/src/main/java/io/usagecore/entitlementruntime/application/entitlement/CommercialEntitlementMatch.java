package io.usagecore.entitlementruntime.application.entitlement;

import io.usagecore.entitlementruntime.domain.SnapshotEntitlementMode;
import java.util.UUID;

/**
 * Narrow commercial read model for a single entitlement check.
 * Does not expose PlanFeature or Control Plane aggregates.
 */
public record CommercialEntitlementMatch(
        UUID contractId,
        UUID contractVersionId,
        int contractVersionNumber,
        SnapshotEntitlementMode entitlementMode,
        Long configuredLimit
) {
}
