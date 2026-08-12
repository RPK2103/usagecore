package io.usagecore.entitlementruntime.application.entitlement;

import io.usagecore.entitlementruntime.domain.EntitlementDecisionType;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of a successfully evaluated entitlement check (HTTP 200 body source).
 * Does not include remainingQuota / consumedUnits — metering phase later.
 */
public record EntitlementCheckResult(
        UUID decisionId,
        EntitlementDecisionType decision,
        String reason,
        String productKey,
        String featureKey,
        long requestedUnits,
        Long configuredLimit,
        Integer contractVersion,
        Instant evaluatedAt,
        String correlationId
) {
}
