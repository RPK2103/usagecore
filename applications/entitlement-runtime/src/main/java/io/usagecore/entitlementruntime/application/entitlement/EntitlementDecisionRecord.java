package io.usagecore.entitlementruntime.application.entitlement;

import io.usagecore.entitlementruntime.domain.EntitlementDecisionType;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-oriented decision evidence. Not an event-sourcing store.
 * configuredLimit is contractual configuration only — no remainingQuota/consumedUnits.
 */
public record EntitlementDecisionRecord(
        UUID decisionId,
        UUID tenantId,
        String principalId,
        UUID contractId,
        UUID contractVersionId,
        Integer contractVersionNumber,
        String productKey,
        String featureKey,
        long requestedUnits,
        EntitlementDecisionType decision,
        String reason,
        Long configuredLimit,
        Instant evaluatedAt,
        String correlationId,
        Instant createdAt
) {
}
