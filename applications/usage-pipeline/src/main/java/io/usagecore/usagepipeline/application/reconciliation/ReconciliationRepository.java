package io.usagecore.usagepipeline.application.reconciliation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for reconciliation runs and items. Production path is insert/complete/fail only —
 * never UPDATE of completed evidence, never mutation of aggregates/quota.
 */
public interface ReconciliationRepository {

    void insertRunning(ReconciliationRunRecord run);

    void complete(ReconciliationRunRecord run, List<ReconciliationItemRecord> items);

    void markFailed(UUID runId, java.time.Instant completedAt, String failureReason);

    Optional<ReconciliationRunRecord> findRunById(UUID runId);

    Optional<ReconciliationRunRecord> findRunByIdAndTenantId(UUID runId, UUID tenantId);

    List<ReconciliationItemRecord> findItemsByRunId(UUID runId);

    boolean existsRunningForPeriod(UUID commercialPeriodId);
}
