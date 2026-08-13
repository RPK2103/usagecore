package io.usagecore.usagepipeline.application.quota;

import java.util.UUID;

/**
 * Result of {@code POST /api/v1/usage/consume}.
 * Commercial REJECTED outcomes are normal results (HTTP 200), not transport failures.
 */
public record QuotaConsumeResult(
        UUID consumptionId,
        UUID eventId,
        QuotaDecision decision,
        String reason,
        String productKey,
        String meterKey,
        String featureKey,
        long quantity,
        long contribution,
        Long configuredLimit,
        Long consumed,
        Long remaining,
        Integer contractVersionNumber,
        String correlationId,
        boolean idempotentReplay
) {
}
