package io.usagecore.usagepipeline.application.quota;

import java.util.UUID;

public record CommercialEntitlementMatch(
        UUID contractId,
        UUID contractVersionId,
        int contractVersionNumber,
        SnapshotEntitlementMode entitlementMode,
        Long configuredLimit
) {
}
