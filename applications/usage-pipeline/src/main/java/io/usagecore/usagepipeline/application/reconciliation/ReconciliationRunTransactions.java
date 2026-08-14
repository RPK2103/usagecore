package io.usagecore.usagepipeline.application.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit transaction boundaries for reconciliation runs (avoids self-invocation proxy issues).
 */
@Component
public class ReconciliationRunTransactions {

    private final ReconciliationRepository reconciliationRepository;

    public ReconciliationRunTransactions(ReconciliationRepository reconciliationRepository) {
        this.reconciliationRepository = reconciliationRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertRunning(ReconciliationRunRecord run) {
        reconciliationRepository.insertRunning(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID runId, Instant completedAt, String failureReason) {
        reconciliationRepository.markFailed(runId, completedAt, failureReason);
    }

    @Transactional
    public ReconciliationRunRecord executeInTransaction(Supplier<ReconciliationRunRecord> work) {
        return work.get();
    }

    @Transactional
    public void complete(ReconciliationRunRecord run, List<ReconciliationItemRecord> items) {
        reconciliationRepository.complete(run, items);
    }
}
