package io.usagecore.usagepipeline.application.reconciliation;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageWindow;
import io.usagecore.usagepipeline.application.usage.UsageWindowResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic rebuild of expected commercial window aggregates from canonical ledger evidence.
 * Does not read or mutate {@code usage_window_aggregate} / {@code usage_aggregate} / {@code quota_state}
 * as rebuild inputs — those are comparison targets only.
 * <p>
 * Meter semantics ({@code aggregationType}, {@code aggregationWindow}) come from the current
 * immutable {@code meter_definition} row. Historical rebuild is deterministic only while
 * semantic meter configuration remains immutable (Phase 6B invariant).
 */
@Component
public class DeterministicRebuildEngine {

    private final UsageWindowResolver usageWindowResolver;

    public DeterministicRebuildEngine(UsageWindowResolver usageWindowResolver) {
        this.usageWindowResolver = usageWindowResolver;
    }

    public RebuildResult rebuild(
            ReconciliationEvidenceReader.PeriodSnapshot period,
            List<ReconciliationEvidenceReader.MeterSnapshot> meters,
            List<ReconciliationEvidenceReader.LedgerEventSnapshot> ledgerEvents,
            Set<UUID> quarantinedEventIds,
            List<ReconciliationEvidenceReader.WindowAggregateSnapshot> persistedWindows,
            QuotaLookup quotaLookup
    ) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(meters, "meters");
        Objects.requireNonNull(ledgerEvents, "ledgerEvents");
        Objects.requireNonNull(quarantinedEventIds, "quarantinedEventIds");
        Objects.requireNonNull(persistedWindows, "persistedWindows");
        Objects.requireNonNull(quotaLookup, "quotaLookup");

        List<ReconciliationItemRecord> items = new ArrayList<>();
        long canonicalEventCount = ledgerEvents.size();
        long quarantinedEventCount = ledgerEvents.stream()
                .filter(e -> quarantinedEventIds.contains(e.eventId()))
                .count();

        Map<UUID, ReconciliationEvidenceReader.MeterSnapshot> metersById = new HashMap<>();
        Map<String, ReconciliationEvidenceReader.MeterSnapshot> metersByKey = new HashMap<>();
        for (ReconciliationEvidenceReader.MeterSnapshot meter : meters) {
            metersById.put(meter.meterDefinitionId(), meter);
            metersByKey.put(meter.meterKey(), meter);
        }

        Map<WindowKey, List<ReconciliationEvidenceReader.LedgerEventSnapshot>> observedByWindow = new HashMap<>();
        Map<WindowKey, List<ReconciliationEvidenceReader.LedgerEventSnapshot>> commercialByWindow = new HashMap<>();
        Map<WindowKey, List<ReconciliationEvidenceReader.LedgerEventSnapshot>> quarantinedByWindow = new HashMap<>();

        for (ReconciliationEvidenceReader.LedgerEventSnapshot event : ledgerEvents) {
            ReconciliationEvidenceReader.MeterSnapshot meter = metersByKey.get(event.meterKey());
            if (meter == null) {
                continue;
            }
            UsageWindow window = usageWindowResolver.resolve(event.occurredAt(), meter.aggregationWindow());
            WindowKey key = new WindowKey(meter.meterDefinitionId(), window.start(), window.end());
            observedByWindow.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
            if (quarantinedEventIds.contains(event.eventId())) {
                quarantinedByWindow.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
            } else {
                commercialByWindow.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
            }
        }

        Map<WindowKey, ReconciliationEvidenceReader.WindowAggregateSnapshot> actualByWindow = new HashMap<>();
        for (ReconciliationEvidenceReader.WindowAggregateSnapshot actual : persistedWindows) {
            if (!metersById.containsKey(actual.meterDefinitionId())) {
                continue;
            }
            // Only compare windows that intersect the commercial period half-open range.
            if (actual.windowEnd().compareTo(period.periodStart()) <= 0
                    || actual.windowStart().compareTo(period.periodEnd()) >= 0) {
                continue;
            }
            actualByWindow.put(
                    new WindowKey(actual.meterDefinitionId(), actual.windowStart(), actual.windowEnd()),
                    actual
            );
        }

        Set<WindowKey> allKeys = new HashSet<>();
        allKeys.addAll(observedByWindow.keySet());
        allKeys.addAll(actualByWindow.keySet());

        List<WindowKey> orderedKeys = allKeys.stream()
                .sorted(Comparator
                        .comparing((WindowKey k) -> metersById.get(k.meterDefinitionId()).meterKey())
                        .thenComparing(WindowKey::windowStart)
                        .thenComparing(WindowKey::windowEnd))
                .toList();

        for (WindowKey key : orderedKeys) {
            ReconciliationEvidenceReader.MeterSnapshot meter = metersById.get(key.meterDefinitionId());
            List<ReconciliationEvidenceReader.LedgerEventSnapshot> observed =
                    observedByWindow.getOrDefault(key, List.of());
            List<ReconciliationEvidenceReader.LedgerEventSnapshot> commercial =
                    commercialByWindow.getOrDefault(key, List.of());
            List<ReconciliationEvidenceReader.LedgerEventSnapshot> quarantined =
                    quarantinedByWindow.getOrDefault(key, List.of());
            ReconciliationEvidenceReader.WindowAggregateSnapshot actual = actualByWindow.get(key);

            long observedExpected = aggregate(meter.aggregationType(), observed);
            long commercialExpected = aggregate(meter.aggregationType(), commercial);
            long expectedEventCount = commercial.size();
            long observedEventCount = observed.size();
            long quarantinedCount = quarantined.size();

            Long actualValue = actual == null ? null : actual.aggregateValue();
            Long actualEventCount = actual == null ? null : actual.eventCount();
            Long difference = actualValue == null ? null : actualValue - commercialExpected;

            Long quotaConsumed = null;
            if (meter.aggregationType() == AggregationType.SUM || meter.aggregationType() == AggregationType.COUNT) {
                quotaConsumed = quotaLookup.findConsumed(
                        period.tenantId(),
                        meter.meterDefinitionId(),
                        key.windowStart(),
                        key.windowEnd()
                ).orElse(null);
            }

            ClassificationOutcome outcome = classify(
                    commercialExpected,
                    expectedEventCount,
                    actualValue,
                    actualEventCount,
                    quotaConsumed,
                    meter.aggregationType()
            );

            items.add(new ReconciliationItemRecord(
                    UUID.randomUUID(),
                    null,
                    meter.meterDefinitionId(),
                    meter.meterKey(),
                    meter.aggregationType(),
                    key.windowStart(),
                    key.windowEnd(),
                    observedExpected,
                    commercialExpected,
                    actualValue,
                    difference,
                    expectedEventCount,
                    actualEventCount,
                    quarantinedCount,
                    observedEventCount,
                    quotaConsumed,
                    outcome.status(),
                    outcome.classification()
            ));
        }

        int matched = (int) items.stream().filter(i -> i.status() == ReconciliationItemStatus.MATCH).count();
        int mismatched = items.size() - matched;
        ReconciliationResult result = mismatched == 0 ? ReconciliationResult.MATCH : ReconciliationResult.MISMATCH;

        return new RebuildResult(
                canonicalEventCount,
                quarantinedEventCount,
                matched,
                mismatched,
                result,
                items
        );
    }

    static long aggregate(AggregationType type, List<ReconciliationEvidenceReader.LedgerEventSnapshot> events) {
        if (events.isEmpty()) {
            return 0L;
        }
        return switch (type) {
            case SUM -> events.stream().mapToLong(ReconciliationEvidenceReader.LedgerEventSnapshot::quantity).sum();
            case COUNT -> events.size();
            case MAX -> events.stream().mapToLong(ReconciliationEvidenceReader.LedgerEventSnapshot::quantity).max().orElse(0L);
        };
    }

    static ClassificationOutcome classify(
            long commercialExpected,
            long expectedEventCount,
            Long actualValue,
            Long actualEventCount,
            Long quotaConsumed,
            AggregationType aggregationType
    ) {
        boolean hasExpected = expectedEventCount > 0 || commercialExpected > 0;
        if (!hasExpected && actualValue == null) {
            return new ClassificationOutcome(ReconciliationItemStatus.MATCH, ReconciliationClassification.MATCH);
        }
        if (hasExpected && actualValue == null) {
            return new ClassificationOutcome(
                    ReconciliationItemStatus.MISMATCH,
                    ReconciliationClassification.MISSING_AGGREGATE
            );
        }
        if (!hasExpected && actualValue != null) {
            return new ClassificationOutcome(
                    ReconciliationItemStatus.MISMATCH,
                    ReconciliationClassification.UNEXPECTED_AGGREGATE
            );
        }
        if (!actualValue.equals(commercialExpected)) {
            return new ClassificationOutcome(
                    ReconciliationItemStatus.MISMATCH,
                    ReconciliationClassification.AGGREGATE_VALUE_MISMATCH
            );
        }
        if (actualEventCount != null && actualEventCount != expectedEventCount) {
            return new ClassificationOutcome(
                    ReconciliationItemStatus.MISMATCH,
                    ReconciliationClassification.EVENT_COUNT_MISMATCH
            );
        }
        if ((aggregationType == AggregationType.SUM || aggregationType == AggregationType.COUNT)
                && quotaConsumed != null
                && !quotaConsumed.equals(commercialExpected)) {
            return new ClassificationOutcome(
                    ReconciliationItemStatus.MISMATCH,
                    ReconciliationClassification.QUOTA_REPORTING_DIVERGENCE
            );
        }
        return new ClassificationOutcome(ReconciliationItemStatus.MATCH, ReconciliationClassification.MATCH);
    }

    public record RebuildResult(
            long canonicalEventCount,
            long quarantinedEventCount,
            int matchedMeterCount,
            int mismatchedMeterCount,
            ReconciliationResult result,
            List<ReconciliationItemRecord> items
    ) {
    }

    public record ClassificationOutcome(
            ReconciliationItemStatus status,
            ReconciliationClassification classification
    ) {
    }

    @FunctionalInterface
    public interface QuotaLookup {
        Optional<Long> findConsumed(
                UUID tenantId,
                UUID meterDefinitionId,
                java.time.Instant windowStart,
                java.time.Instant windowEnd
        );
    }

    private record WindowKey(UUID meterDefinitionId, java.time.Instant windowStart, java.time.Instant windowEnd) {
    }
}
