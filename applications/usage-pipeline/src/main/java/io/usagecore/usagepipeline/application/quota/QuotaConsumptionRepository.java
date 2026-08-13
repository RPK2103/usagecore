package io.usagecore.usagepipeline.application.quota;

import java.util.Optional;
import java.util.UUID;

public interface QuotaConsumptionRepository {

    /**
     * Transaction-scoped advisory lock so identical idempotency keys serialize before
     * any quota mutation. Different keys do not contend.
     */
    void acquireIdempotencyLock(UUID tenantId, String idempotencyKey);

    Optional<UUID> insertIfAbsent(QuotaConsumptionRecord record);

    Optional<QuotaConsumptionRecord> findByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    long countByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey);
}
