package io.usagecore.entitlementruntime.application.entitlement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for resolving activated commercial entitlement state at an evaluation instant.
 * Implementations must not read live PlanFeature rows.
 */
public interface CommercialEntitlementReader {

    /**
     * Returns zero, one, or (invariant failure) more commercial matches for
     * tenant + productKey + featureKey effective at evaluationInstant.
     * Callers must fail loudly when size &gt; 1 — do not ORDER BY/LIMIT to hide ambiguity.
     */
    List<CommercialEntitlementMatch> findEffectiveEntitlements(
            UUID tenantId,
            String productKey,
            String featureKey,
            Instant evaluationInstant
    );
}
