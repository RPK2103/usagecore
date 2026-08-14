package io.usagecore.usagepipeline.application.reconciliation;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.AggregationWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only data access for deterministic rebuild inputs. Must not read derived aggregates
 * as rebuild sources — only for comparison after rebuild.
 */
public interface ReconciliationEvidenceReader {

    Optional<PeriodSnapshot> findPeriodById(UUID commercialPeriodId);

    Optional<PeriodSnapshot> findPeriodByIdForShare(UUID commercialPeriodId);

    String requireProductKey(UUID productId);

    List<MeterSnapshot> findActiveMetersByProductId(UUID productId);

    List<LedgerEventSnapshot> findLedgerEvents(
            UUID tenantId,
            String productKey,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    );

    Set<UUID> findQuarantinedEventIds(UUID commercialPeriodId);

    List<WindowAggregateSnapshot> findWindowAggregatesOverlapping(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    );

    Optional<Long> findQuotaConsumed(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd
    );

    record PeriodSnapshot(
            UUID id,
            UUID tenantId,
            UUID productId,
            Instant periodStart,
            Instant periodEnd,
            String status
    ) {
    }

    record MeterSnapshot(
            UUID meterDefinitionId,
            UUID productId,
            String meterKey,
            AggregationType aggregationType,
            AggregationWindow aggregationWindow
    ) {
    }

    record LedgerEventSnapshot(
            UUID eventId,
            String meterKey,
            long quantity,
            Instant occurredAt
    ) {
    }

    record WindowAggregateSnapshot(
            UUID meterDefinitionId,
            String meterKey,
            AggregationType aggregationType,
            Instant windowStart,
            Instant windowEnd,
            long aggregateValue,
            long eventCount
    ) {
    }
}
