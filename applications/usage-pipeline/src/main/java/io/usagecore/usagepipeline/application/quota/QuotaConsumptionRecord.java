package io.usagecore.usagepipeline.application.quota;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record QuotaConsumptionRecord(
        UUID id,
        UUID eventId,
        UUID tenantId,
        String principalId,
        String productKey,
        String meterKey,
        UUID meterDefinitionId,
        String featureKey,
        long quantity,
        long contribution,
        Instant occurredAt,
        Instant windowStart,
        Instant windowEnd,
        String idempotencyKey,
        String correlationId,
        QuotaDecision decision,
        String reason,
        Long configuredLimit,
        Long consumedAfter,
        Long remainingAfter,
        UUID contractVersionId,
        Integer contractVersionNumber,
        Instant decidedAt
) {
}
