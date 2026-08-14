package io.usagecore.usagepipeline.adapters.inbound.http.reconciliation;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunRecord;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunResponse(
        UUID reconciliationRunId,
        UUID tenantId,
        UUID productId,
        UUID commercialPeriodId,
        String status,
        String result,
        Instant startedAt,
        Instant completedAt,
        String startedBy,
        Long canonicalEventCount,
        Long quarantinedEventCount,
        Integer matchedMeters,
        Integer mismatchedMeters,
        String correlationId,
        String failureReason
) {

    public static ReconciliationRunResponse from(ReconciliationRunRecord run) {
        return new ReconciliationRunResponse(
                run.id(),
                run.tenantId(),
                run.productId(),
                run.commercialPeriodId(),
                run.status().name(),
                run.result() == null ? null : run.result().name(),
                run.startedAt(),
                run.completedAt(),
                run.startedBy(),
                run.canonicalEventCount(),
                run.quarantinedEventCount(),
                run.matchedMeterCount(),
                run.mismatchedMeterCount(),
                run.correlationId(),
                run.failureReason()
        );
    }
}
