package io.usagecore.usagepipeline.application.reconciliation;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-meter/window comparison evidence for a reconciliation run.
 * {@code actualValue} null means no persisted {@code usage_window_aggregate} row.
 * {@code difference} is {@code actual - commercialExpected} when actual is present.
 */
public record ReconciliationItemRecord(
        UUID id,
        UUID reconciliationRunId,
        UUID meterDefinitionId,
        String meterKey,
        AggregationType aggregationType,
        Instant windowStart,
        Instant windowEnd,
        long observedExpectedValue,
        long commercialExpectedValue,
        Long actualValue,
        Long difference,
        long expectedEventCount,
        Long actualEventCount,
        long quarantinedEventCount,
        long observedEventCount,
        Long quotaConsumedValue,
        ReconciliationItemStatus status,
        ReconciliationClassification classification
) {
}
