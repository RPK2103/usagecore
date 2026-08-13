package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Derived per-tenant meter aggregate state. Rebuildable from {@code usage_ledger}.
 */
public record UsageAggregateRecord(
        UUID id,
        UUID tenantId,
        UUID productId,
        UUID meterDefinitionId,
        String meterKey,
        AggregationType aggregationType,
        long aggregateValue,
        long eventCount,
        Instant lastEventAt,
        Instant updatedAt
) {
}
