package io.usagecore.usagepipeline.application.adjustment;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable applied UsageAdjustment evidence. Production code must not UPDATE or DELETE rows.
 */
public record UsageAdjustmentRecord(
        UUID id,
        UUID tenantId,
        UUID productId,
        UUID meterDefinitionId,
        String meterKey,
        UUID commercialPeriodId,
        UUID commercialUsageExceptionId,
        UUID sourceEventId,
        UUID reconciliationRunId,
        AdjustmentType adjustmentType,
        AggregationType aggregationType,
        long quantity,
        long aggregateValueContribution,
        long eventCountContribution,
        Instant windowStart,
        Instant windowEnd,
        String idempotencyKey,
        String reason,
        Instant appliedAt,
        String appliedBy,
        String correlationId
) {
}
