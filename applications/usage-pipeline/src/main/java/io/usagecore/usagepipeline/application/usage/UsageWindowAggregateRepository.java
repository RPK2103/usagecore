package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Derived event-time window aggregate. Concurrent updates use PostgreSQL UPSERT.
 */
public interface UsageWindowAggregateRepository {

    void applyEvent(
            UUID tenantId,
            ActiveMeterDefinition meter,
            UsageWindow window,
            long quantity,
            Instant occurredAt,
            Instant updatedAt
    );

    Optional<UsageWindowAggregateRecord> findByTenantMeterAndWindow(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd
    );

    Optional<UsageWindowAggregateRecord> findByTenantProductMeterAndWindow(
            UUID tenantId,
            String productKey,
            String meterKey,
            Instant windowStart,
            Instant windowEnd
    );

    List<UsageWindowAggregateRecord> findByTenantProductMeterOverlapping(
            UUID tenantId,
            String productKey,
            String meterKey,
            Instant fromInclusive,
            Instant toExclusive
    );

    long countAll();
}
