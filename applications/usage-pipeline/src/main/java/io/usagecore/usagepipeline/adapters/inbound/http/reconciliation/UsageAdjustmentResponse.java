package io.usagecore.usagepipeline.adapters.inbound.http.reconciliation;

import io.usagecore.usagepipeline.application.adjustment.UsageAdjustmentRecord;
import java.time.Instant;
import java.util.UUID;

public record UsageAdjustmentResponse(
        UUID adjustmentId,
        String adjustmentType,
        UUID sourceEventId,
        UUID commercialPeriodId,
        UUID commercialUsageExceptionId,
        UUID reconciliationRunId,
        String meterKey,
        String aggregationType,
        long aggregateValueContribution,
        long eventCountContribution,
        Instant windowStart,
        Instant windowEnd,
        String status
) {

    public static UsageAdjustmentResponse from(UsageAdjustmentRecord record) {
        return new UsageAdjustmentResponse(
                record.id(),
                record.adjustmentType().name(),
                record.sourceEventId(),
                record.commercialPeriodId(),
                record.commercialUsageExceptionId(),
                record.reconciliationRunId(),
                record.meterKey(),
                record.aggregationType().name(),
                record.aggregateValueContribution(),
                record.eventCountContribution(),
                record.windowStart(),
                record.windowEnd(),
                "APPLIED"
        );
    }
}
