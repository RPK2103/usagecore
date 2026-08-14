package io.usagecore.usagepipeline.application.adjustment;

public final class AdjustmentErrorCodes {

    public static final String ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD = "ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD";
    public static final String ADJUSTMENT_ALREADY_APPLIED = "ADJUSTMENT_ALREADY_APPLIED";
    public static final String RECONCILIATION_RUN_NOT_COMPLETED = "RECONCILIATION_RUN_NOT_COMPLETED";
    public static final String ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION =
            "ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION";

    private AdjustmentErrorCodes() {
    }
}
