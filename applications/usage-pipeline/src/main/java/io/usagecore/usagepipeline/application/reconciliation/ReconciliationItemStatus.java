package io.usagecore.usagepipeline.application.reconciliation;

/**
 * Per-item MATCH / MISMATCH (aggregate comparison + optional quota divergence).
 */
public enum ReconciliationItemStatus {
    MATCH,
    MISMATCH
}
