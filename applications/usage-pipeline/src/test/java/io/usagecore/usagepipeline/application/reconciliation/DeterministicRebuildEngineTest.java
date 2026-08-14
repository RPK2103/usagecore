package io.usagecore.usagepipeline.application.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.AggregationWindow;
import io.usagecore.usagepipeline.application.usage.UsageWindowResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicRebuildEngineTest {

    private final DeterministicRebuildEngine engine = new DeterministicRebuildEngine(new UsageWindowResolver());

    @Test
    void sumCountMax_emptyStateIsZero() {
        assertThat(DeterministicRebuildEngine.aggregate(AggregationType.SUM, List.of())).isZero();
        assertThat(DeterministicRebuildEngine.aggregate(AggregationType.COUNT, List.of())).isZero();
        assertThat(DeterministicRebuildEngine.aggregate(AggregationType.MAX, List.of())).isZero();
    }

    @Test
    void sumCountMax_matchLiveSemantics() {
        var events = List.of(
                new ReconciliationEvidenceReader.LedgerEventSnapshot(UUID.randomUUID(), "m", 10, Instant.parse("2026-08-01T00:00:00Z")),
                new ReconciliationEvidenceReader.LedgerEventSnapshot(UUID.randomUUID(), "m", 25, Instant.parse("2026-08-02T00:00:00Z")),
                new ReconciliationEvidenceReader.LedgerEventSnapshot(UUID.randomUUID(), "m", 5, Instant.parse("2026-08-03T00:00:00Z"))
        );
        assertThat(DeterministicRebuildEngine.aggregate(AggregationType.SUM, events)).isEqualTo(40);
        assertThat(DeterministicRebuildEngine.aggregate(AggregationType.COUNT, events)).isEqualTo(3);
        assertThat(DeterministicRebuildEngine.aggregate(AggregationType.MAX, events)).isEqualTo(25);
    }

    @Test
    void quarantineExcludedFromCommercialExpected() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        UUID meterId = UUID.randomUUID();
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();

        var period = new ReconciliationEvidenceReader.PeriodSnapshot(
                periodId,
                tenantId,
                productId,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                "RECONCILING"
        );
        var meter = new ReconciliationEvidenceReader.MeterSnapshot(
                meterId, productId, "api_requests", AggregationType.SUM, AggregationWindow.MONTHLY
        );
        var ledger = List.of(
                new ReconciliationEvidenceReader.LedgerEventSnapshot(
                        applied, "api_requests", 97, Instant.parse("2026-08-15T00:00:00Z")),
                new ReconciliationEvidenceReader.LedgerEventSnapshot(
                        quarantined, "api_requests", 3, Instant.parse("2026-08-16T00:00:00Z"))
        );
        var actual = List.of(new ReconciliationEvidenceReader.WindowAggregateSnapshot(
                meterId,
                "api_requests",
                AggregationType.SUM,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                97,
                1
        ));

        var result = engine.rebuild(
                period,
                List.of(meter),
                ledger,
                Set.of(quarantined),
                Set.of(),
                actual,
                (t, m, ws, we) -> Optional.empty()
        );

        assertThat(result.canonicalEventCount()).isEqualTo(2);
        assertThat(result.quarantinedEventCount()).isEqualTo(1);
        assertThat(result.result()).isEqualTo(ReconciliationResult.MATCH);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().observedExpectedValue()).isEqualTo(100);
        assertThat(result.items().getFirst().commercialExpectedValue()).isEqualTo(97);
        assertThat(result.items().getFirst().quarantinedEventCount()).isEqualTo(1);
        assertThat(result.items().getFirst().adjustedEventCount()).isZero();
        assertThat(result.items().getFirst().unresolvedExceptionCount()).isEqualTo(1);
    }

    @Test
    void appliedAdjustment_reclassifiesQuarantineAsCommercialExpected() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        UUID meterId = UUID.randomUUID();
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();

        var period = new ReconciliationEvidenceReader.PeriodSnapshot(
                periodId,
                tenantId,
                productId,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                "FINALIZED"
        );
        var meter = new ReconciliationEvidenceReader.MeterSnapshot(
                meterId, productId, "api_requests", AggregationType.SUM, AggregationWindow.MONTHLY
        );
        var ledger = List.of(
                new ReconciliationEvidenceReader.LedgerEventSnapshot(
                        applied, "api_requests", 97, Instant.parse("2026-08-15T00:00:00Z")),
                new ReconciliationEvidenceReader.LedgerEventSnapshot(
                        quarantined, "api_requests", 3, Instant.parse("2026-08-16T00:00:00Z"))
        );
        var actual = List.of(new ReconciliationEvidenceReader.WindowAggregateSnapshot(
                meterId,
                "api_requests",
                AggregationType.SUM,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                100,
                2
        ));

        var result = engine.rebuild(
                period,
                List.of(meter),
                ledger,
                Set.of(quarantined),
                Set.of(quarantined),
                actual,
                (t, m, ws, we) -> Optional.empty()
        );

        assertThat(result.result()).isEqualTo(ReconciliationResult.MATCH);
        assertThat(result.items().getFirst().commercialExpectedValue()).isEqualTo(100);
        assertThat(result.items().getFirst().expectedEventCount()).isEqualTo(2);
        assertThat(result.items().getFirst().adjustedEventCount()).isEqualTo(1);
        assertThat(result.items().getFirst().unresolvedExceptionCount()).isZero();
        assertThat(result.items().getFirst().quarantinedEventCount()).isEqualTo(1);
    }

    @Test
    void classify_missingUnexpectedValueCountQuota() {
        assertThat(DeterministicRebuildEngine.classify(60, 3, null, null, null, AggregationType.SUM).classification())
                .isEqualTo(ReconciliationClassification.MISSING_AGGREGATE);
        assertThat(DeterministicRebuildEngine.classify(0, 0, 42L, 1L, null, AggregationType.SUM).classification())
                .isEqualTo(ReconciliationClassification.UNEXPECTED_AGGREGATE);
        assertThat(DeterministicRebuildEngine.classify(40, 3, 37L, 3L, null, AggregationType.SUM).classification())
                .isEqualTo(ReconciliationClassification.AGGREGATE_VALUE_MISMATCH);
        assertThat(DeterministicRebuildEngine.classify(40, 2, 40L, 9L, null, AggregationType.SUM).classification())
                .isEqualTo(ReconciliationClassification.EVENT_COUNT_MISMATCH);
        assertThat(DeterministicRebuildEngine.classify(97, 2, 97L, 2L, 100L, AggregationType.SUM).classification())
                .isEqualTo(ReconciliationClassification.QUOTA_REPORTING_DIVERGENCE);
        assertThat(DeterministicRebuildEngine.classify(40, 3, 40L, 3L, null, AggregationType.SUM).classification())
                .isEqualTo(ReconciliationClassification.MATCH);
    }

    @Test
    void sanitizeFailureReason_redactsAndBounds() {
        String reason = ReconciliationApplicationService.sanitizeFailureReason(
                new IllegalStateException("boom password=supersecret and more detail ".repeat(40))
        );
        assertThat(reason).doesNotContain("supersecret");
        assertThat(reason.length()).isLessThanOrEqualTo(480);
    }
}
