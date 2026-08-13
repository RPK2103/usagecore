package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Derived temporal aggregate for one tenant + meter + event-time window.
 */
public record UsageWindowAggregateRecord(
        UUID id,
        UUID tenantId,
        UUID productId,
        UUID meterDefinitionId,
        String meterKey,
        AggregationType aggregationType,
        Instant windowStart,
        Instant windowEnd,
        long aggregateValue,
        long eventCount,
        Instant firstEventAt,
        Instant lastEventAt,
        Instant updatedAt
) {
}
