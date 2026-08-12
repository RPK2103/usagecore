package io.usagecore.entitlementruntime.adapters.inbound.http.entitlement;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.usagecore.entitlementruntime.domain.EntitlementDecisionType;
import java.time.Instant;
import java.util.UUID;

/**
 * Deterministic entitlement check response.
 * configuredLimit is contractual configuration only — no remainingQuota / consumedUnits.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckEntitlementResponse(
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
