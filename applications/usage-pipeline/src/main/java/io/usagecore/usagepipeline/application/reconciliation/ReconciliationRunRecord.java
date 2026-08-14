package io.usagecore.usagepipeline.application.reconciliation;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable reconciliation run evidence (persisted). RUNNING rows may complete or fail;
 * COMPLETED / FAILED rows are not rewritten by normal application flows.
 */
public record ReconciliationRunRecord(
        UUID id,
        UUID tenantId,
        UUID productId,
        UUID commercialPeriodId,
        ReconciliationRunStatus status,
        ReconciliationResult result,
        Instant startedAt,
        Instant completedAt,
        String startedBy,
        Long canonicalEventCount,
        Long quarantinedEventCount,
        Integer matchedMeterCount,
        Integer mismatchedMeterCount,
        String correlationId,
        String failureReason
) {
}
