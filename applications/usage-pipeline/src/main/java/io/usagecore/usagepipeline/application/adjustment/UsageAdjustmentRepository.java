package io.usagecore.usagepipeline.application.adjustment;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationEvidenceReader;
import java.util.Optional;
import java.util.UUID;

public interface UsageAdjustmentRepository {

    ReconciliationEvidenceReader.PeriodSnapshot lockPeriodForUpdate(UUID commercialPeriodId);

    Optional<UsageAdjustmentRecord> insert(UsageAdjustmentRecord record);

    Optional<UsageAdjustmentRecord> findById(UUID adjustmentId);

    Optional<UsageAdjustmentRecord> findByTenantIdAndId(UUID tenantId, UUID adjustmentId);

    Optional<UsageAdjustmentRecord> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<UsageAdjustmentRecord> findByCommercialUsageExceptionId(UUID exceptionId);

    Optional<UsageAdjustmentRecord> findBySourceEventId(UUID sourceEventId);

    Optional<ExceptionSnapshot> lockExceptionForUpdate(UUID exceptionId);

    long countAll();

    record ExceptionSnapshot(
            UUID id,
            UUID eventId,
            UUID tenantId,
            UUID productId,
            UUID meterDefinitionId,
            UUID commercialPeriodId,
            String reason,
            java.time.Instant occurredAt
    ) {
    }
}
