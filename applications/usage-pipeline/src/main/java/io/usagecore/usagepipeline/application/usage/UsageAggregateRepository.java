package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UsageAggregateRepository {

    /**
     * Concurrency-safe atomic upsert for one usage event contribution.
     * PostgreSQL is the authority for concurrent mutation (SUM/COUNT add, MAX via GREATEST).
     */
    void applyEvent(
            UUID tenantId,
            ActiveMeterDefinition meter,
            long quantity,
            Instant occurredAt,
            Instant updatedAt
    );

    Optional<UsageAggregateRecord> findByTenantAndMeterDefinition(UUID tenantId, UUID meterDefinitionId);

    Optional<UsageAggregateRecord> findByTenantProductKeyAndMeterKey(
            UUID tenantId,
            String productKey,
            String meterKey
    );

    long countAll();
}
