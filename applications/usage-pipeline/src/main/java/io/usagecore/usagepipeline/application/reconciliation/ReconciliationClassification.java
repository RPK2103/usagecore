package io.usagecore.usagepipeline.application.reconciliation;

/**
 * Deterministic mismatch / match classification for a reconciliation item.
 * Small taxonomy — quarantined usage is reported as counts, not corruption.
 */
public enum ReconciliationClassification {
    MATCH,
    AGGREGATE_VALUE_MISMATCH,
    EVENT_COUNT_MISMATCH,
    MISSING_AGGREGATE,
    UNEXPECTED_AGGREGATE,
    QUOTA_REPORTING_DIVERGENCE
}
