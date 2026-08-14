package io.usagecore.usagepipeline.adapters.inbound.http.reconciliation;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationItemRecord;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationItemResponse(
        UUID id,
        UUID reconciliationRunId,
        UUID meterDefinitionId,
        String meterKey,
        String aggregationType,
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
        long adjustedEventCount,
        long unresolvedExceptionCount,
        Long quotaConsumedValue,
        String status,
        String classification
) {

    public static ReconciliationItemResponse from(ReconciliationItemRecord item) {
        return new ReconciliationItemResponse(
                item.id(),
                item.reconciliationRunId(),
                item.meterDefinitionId(),
                item.meterKey(),
                item.aggregationType().name(),
                item.windowStart(),
                item.windowEnd(),
                item.observedExpectedValue(),
                item.commercialExpectedValue(),
                item.actualValue(),
                item.difference(),
                item.expectedEventCount(),
                item.actualEventCount(),
                item.quarantinedEventCount(),
                item.observedEventCount(),
                item.adjustedEventCount(),
                item.unresolvedExceptionCount(),
                item.quotaConsumedValue(),
                item.status().name(),
                item.classification().name()
        );
    }
}
