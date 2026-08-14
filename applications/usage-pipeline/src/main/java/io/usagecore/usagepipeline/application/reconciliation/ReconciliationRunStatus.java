package io.usagecore.usagepipeline.application.reconciliation;

/**
 * Lifecycle of a reconciliation run. Completed / failed runs are immutable evidence.
 */
public enum ReconciliationRunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
