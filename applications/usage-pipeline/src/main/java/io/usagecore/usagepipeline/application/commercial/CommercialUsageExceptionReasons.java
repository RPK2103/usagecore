package io.usagecore.usagepipeline.application.commercial;

/**
 * Reasons recorded on {@code commercial_usage_exception} for blocked late/async usage.
 * Not UsageAdjustment — quarantine evidence for Phase 8.
 */
public final class CommercialUsageExceptionReasons {

    public static final String PERIOD_RECONCILING = "PERIOD_RECONCILING";
    public static final String PERIOD_FINALIZED = "PERIOD_FINALIZED";

    private CommercialUsageExceptionReasons() {
    }
}
