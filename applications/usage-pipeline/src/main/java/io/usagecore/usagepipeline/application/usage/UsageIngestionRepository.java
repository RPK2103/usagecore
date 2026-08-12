package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UsageIngestionRepository {

    /**
     * Inserts a new ingestion row. Returns empty when {@code (tenant_id, idempotency_key)}
     * already exists (PostgreSQL unique constraint / ON CONFLICT DO NOTHING).
     */
    Optional<UUID> insertIfAbsent(UsageIngestionRecord record);

    Optional<UsageIngestionRecord> findByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    long countByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey);
}
