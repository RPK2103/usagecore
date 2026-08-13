package io.usagecore.usagepipeline.application.quota;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Narrow read of activated ContractVersion entitlement snapshots.
 * Does not read live Plan rows.
 */
public interface CommercialEntitlementLookup {

    List<CommercialEntitlementMatch> findEffectiveEntitlements(
            UUID tenantId,
            String productKey,
            String featureKey,
            Instant evaluationInstant
    );
}
