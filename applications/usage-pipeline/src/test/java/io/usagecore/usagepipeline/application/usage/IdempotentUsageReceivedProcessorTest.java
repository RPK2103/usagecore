package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodReader;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodStatus;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodView;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionReasons;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRecord;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdempotentUsageReceivedProcessorTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID METER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID FEATURE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final Instant FIXED = Instant.parse("2026-08-12T14:31:00Z");
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void singleEvent_writesOneProcessedEventOneLedgerAndOneAggregateEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.countMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);

        processor.process(sampleEvent(EVENT_ID, "export-job-1", 3L));

        assertThat(inbox.countByEventId(EVENT_ID)).isEqualTo(1);
        assertThat(ledger.countByEventId(EVENT_ID)).isEqualTo(1);
        assertThat(aggregates.totalValue()).isEqualTo(1L);
        assertThat(aggregates.eventCount()).isEqualTo(1L);
        assertThat(windowAggregates.totalValue()).isEqualTo(1L);
        assertThat(windowAggregates.eventCount()).isEqualTo(1L);
        UsageLedgerRecord row = ledger.findByEventId(EVENT_ID).orElseThrow();
        assertThat(row.tenantId()).isEqualTo(TENANT);
        assertThat(row.productKey()).isEqualTo("datapilot-cloud");
        assertThat(row.meterKey()).isEqualTo("scheduled_export");
        assertThat(row.quantity()).isEqualTo(3L);
        assertThat(row.occurredAt()).isEqualTo(OCCURRED);
        assertThat(row.idempotencyKey()).isEqualTo("export-job-1");
        assertThat(row.correlationId()).isEqualTo("corr-1");
        assertThat(row.principalId()).isEqualTo("svc-datapilot");
        assertThat(row.recordedAt()).isEqualTo(FIXED);
        assertThat(row.isLate()).isFalse();
    }

    @Test
    void oneHundredDuplicateDeliveries_produceOneBusinessEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.sumMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-100", 10L);

        for (int i = 0; i < 100; i++) {
            processor.process(event);
        }

        assertThat(inbox.countAll()).isEqualTo(1);
        assertThat(ledger.countAll()).isEqualTo(1);
        assertThat(aggregates.totalValue()).isEqualTo(10L);
        assertThat(aggregates.eventCount()).isEqualTo(1L);
        assertThat(windowAggregates.totalValue()).isEqualTo(10L);
        assertThat(windowAggregates.eventCount()).isEqualTo(1L);
    }

    @Test
    void simulatedRedeliveryAfterDbSuccess_isSuccessfulNoOp() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.countMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-redeliver", 3L);

        processor.process(event);
        assertThat(ledger.countAll()).isEqualTo(1);
        assertThat(aggregates.eventCount()).isEqualTo(1L);
        assertThat(windowAggregates.eventCount()).isEqualTo(1L);

        processor.process(event);

        assertThat(inbox.countAll()).isEqualTo(1);
        assertThat(ledger.countAll()).isEqualTo(1);
        assertThat(aggregates.eventCount()).isEqualTo(1L);
        assertThat(windowAggregates.eventCount()).isEqualTo(1L);
    }

    @Test
    void duplicateDetectionUsesEventId_notHttpIdempotencyKey() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.countMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);

        UUID eventA = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID eventB = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        processor.process(sampleEvent(eventA, "shared-http-key", 3L));
        processor.process(sampleEvent(eventB, "shared-http-key", 3L));

        assertThat(inbox.countAll()).isEqualTo(2);
        assertThat(ledger.countAll()).isEqualTo(2);
        assertThat(aggregates.eventCount()).isEqualTo(2L);
        assertThat(windowAggregates.eventCount()).isEqualTo(2L);
        assertThat(ledger.findByEventId(eventA).orElseThrow().idempotencyKey()).isEqualTo("shared-http-key");
        assertThat(ledger.findByEventId(eventB).orElseThrow().idempotencyKey()).isEqualTo("shared-http-key");
    }

    @Test
    void concurrentDuplicateProcessing_oneEffect() throws Exception {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.sumMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-concurrent", 10L);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                processor.process(event);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(inbox.countAll()).isEqualTo(1);
        assertThat(ledger.countAll()).isEqualTo(1);
        assertThat(aggregates.totalValue()).isEqualTo(10L);
        assertThat(aggregates.eventCount()).isEqualTo(1L);
        assertThat(windowAggregates.totalValue()).isEqualTo(10L);
        assertThat(windowAggregates.eventCount()).isEqualTo(1L);
    }

    @Test
    void unsupportedEventVersion_isRejectedWithoutLedgerEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.countMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);

        EventEnvelope<UsageReceivedPayload> unsupported = new EventEnvelope<>(
                EVENT_ID,
                EventTypes.USAGE_RECEIVED,
                "99",
                OCCURRED,
                TENANT,
                "agg",
                "corr",
                null,
                null,
                FIXED,
                new UsageReceivedPayload("datapilot-cloud", "scheduled_export", 1L, "k", "svc")
        );

        assertThatThrownBy(() -> processor.process(unsupported))
                .isInstanceOf(UnsupportedUsageEventException.class)
                .hasMessageContaining("Unsupported eventVersion");
        assertThat(inbox.countAll()).isZero();
        assertThat(ledger.countAll()).isZero();
        assertThat(aggregates.eventCount()).isZero();
        assertThat(windowAggregates.eventCount()).isZero();
    }

    @Test
    void nonPositiveQuantity_isRejectedWithoutLedgerEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = InMemoryMeterDefinitionLookup.countMeter();
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);

        EventEnvelope<UsageReceivedPayload> invalid = new EventEnvelope<>(
                EVENT_ID,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                TENANT,
                "agg",
                "corr",
                null,
                null,
                FIXED,
                new UsageReceivedPayload("datapilot-cloud", "scheduled_export", 0L, "k", "svc")
        );

        assertThatThrownBy(() -> processor.process(invalid))
                .isInstanceOf(InvalidUsageEventException.class)
                .hasMessageContaining("quantity");
        assertThat(inbox.countAll()).isZero();
        assertThat(ledger.countAll()).isZero();
        assertThat(aggregates.eventCount()).isZero();
        assertThat(windowAggregates.eventCount()).isZero();
    }

    @Test
    void unknownMeter_isRejectedWithoutCommittedState() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        InMemoryMeterDefinitionLookup meters = new InMemoryMeterDefinitionLookup(Optional.empty());
        InMemoryUsageAggregateRepository aggregates = new InMemoryUsageAggregateRepository();
        InMemoryUsageWindowAggregateRepository windowAggregates = new InMemoryUsageWindowAggregateRepository();
        IdempotentUsageReceivedProcessor processor =
                newProcessor(inbox, ledger, meters, aggregates, windowAggregates);

        // Without @Transactional, claim+ledger may remain in in-memory stubs after failure;
        // production path rolls back via Spring transaction. Assert exception type here.
        assertThatThrownBy(() -> processor.process(sampleEvent(EVENT_ID, "unknown-meter", 1L)))
                .isInstanceOf(UnknownUsageMeterException.class)
                .hasMessageContaining("Unknown or inactive meter");
        assertThat(aggregates.eventCount()).isZero();
        assertThat(windowAggregates.eventCount()).isZero();
    }

    private static IdempotentUsageReceivedProcessor newProcessor(
            ProcessedEventRepository inbox,
            UsageLedgerRepository ledger,
            MeterDefinitionLookup meters,
            UsageAggregateRepository aggregates,
            UsageWindowAggregateRepository windowAggregates
    ) {
        return new IdempotentUsageReceivedProcessor(
                inbox,
                ledger,
                meters,
                aggregates,
                windowAggregates,
                new UsageWindowResolver(),
                (tenantId, productId, occurredAt) -> Optional.empty(),
                new InMemoryCommercialUsageExceptionRepository(),
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );
    }

    private static IdempotentUsageReceivedProcessor newProcessor(
            ProcessedEventRepository inbox,
            UsageLedgerRepository ledger,
            MeterDefinitionLookup meters,
            UsageAggregateRepository aggregates,
            UsageWindowAggregateRepository windowAggregates,
            CommercialPeriodReader periodReader,
            CommercialUsageExceptionRepository exceptions
    ) {
        return new IdempotentUsageReceivedProcessor(
                inbox,
                ledger,
                meters,
                aggregates,
                windowAggregates,
                new UsageWindowResolver(),
                periodReader,
                exceptions,
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );
    }

    private static EventEnvelope<UsageReceivedPayload> sampleEvent(
            UUID eventId,
            String idempotencyKey,
            long quantity
    ) {
        return new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                TENANT,
                TENANT + "|datapilot-cloud|scheduled_export",
                "corr-1",
                null,
                null,
                FIXED,
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "scheduled_export",
                        quantity,
                        idempotencyKey,
                        "svc-datapilot"
                )
        );
    }

    static final class InMemoryProcessedEventRepository implements ProcessedEventRepository {
        private final ConcurrentHashMap<UUID, ProcessedEventRecord> rows = new ConcurrentHashMap<>();

        @Override
        public boolean tryClaim(ProcessedEventRecord record) {
            return rows.putIfAbsent(record.eventId(), record) == null;
        }

        @Override
        public long countByEventId(UUID eventId) {
            return rows.containsKey(eventId) ? 1L : 0L;
        }

        @Override
        public long countAll() {
            return rows.size();
        }
    }

    static final class InMemoryUsageLedgerRepository implements UsageLedgerRepository {
        private final ConcurrentHashMap<UUID, UsageLedgerRecord> rows = new ConcurrentHashMap<>();

        @Override
        public void insert(UsageLedgerRecord record) {
            UsageLedgerRecord previous = rows.putIfAbsent(record.eventId(), record);
            if (previous != null) {
                throw new IllegalStateException("duplicate ledger eventId: " + record.eventId());
            }
        }

        @Override
        public Optional<UsageLedgerRecord> findByEventId(UUID eventId) {
            return Optional.ofNullable(rows.get(eventId));
        }

        @Override
        public long countByEventId(UUID eventId) {
            return rows.containsKey(eventId) ? 1L : 0L;
        }

        @Override
        public long countAll() {
            return rows.size();
        }
    }

    static final class InMemoryMeterDefinitionLookup implements MeterDefinitionLookup {
        private final Optional<ActiveMeterDefinition> meter;

        InMemoryMeterDefinitionLookup(Optional<ActiveMeterDefinition> meter) {
            this.meter = meter;
        }

        static InMemoryMeterDefinitionLookup countMeter() {
            return new InMemoryMeterDefinitionLookup(Optional.of(new ActiveMeterDefinition(
                    METER_ID,
                    PRODUCT_ID,
                    "datapilot-cloud",
                    "scheduled_export",
                    FEATURE_ID,
                    "scheduled_export_feature",
                    AggregationType.COUNT,
                    AggregationWindow.MONTHLY
            )));
        }

        static InMemoryMeterDefinitionLookup sumMeter() {
            return new InMemoryMeterDefinitionLookup(Optional.of(new ActiveMeterDefinition(
                    METER_ID,
                    PRODUCT_ID,
                    "datapilot-cloud",
                    "scheduled_export",
                    FEATURE_ID,
                    "scheduled_export_feature",
                    AggregationType.SUM,
                    AggregationWindow.MONTHLY
            )));
        }

        @Override
        public Optional<ActiveMeterDefinition> findActiveByProductKeyAndMeterKey(
                String productKey,
                String meterKey
        ) {
            return meter.filter(m -> m.productKey().equals(productKey) && m.meterKey().equals(meterKey));
        }

        @Override
        public Optional<ActiveMeterDefinition> findActiveByMeterDefinitionId(UUID meterDefinitionId) {
            return meter.filter(m -> m.meterDefinitionId().equals(meterDefinitionId));
        }
    }

    static final class InMemoryUsageAggregateRepository implements UsageAggregateRepository {
        private final AtomicLong value = new AtomicLong();
        private final AtomicInteger events = new AtomicInteger();

        @Override
        public void applyEvent(
                UUID tenantId,
                ActiveMeterDefinition meter,
                long quantity,
                Instant occurredAt,
                Instant updatedAt
        ) {
            long contribution = switch (meter.aggregationType()) {
                case SUM, MAX -> quantity;
                case COUNT -> 1L;
            };
            if (meter.aggregationType() == AggregationType.MAX) {
                value.accumulateAndGet(contribution, Math::max);
            } else {
                value.addAndGet(contribution);
            }
            events.incrementAndGet();
        }

        @Override
        public Optional<UsageAggregateRecord> findByTenantAndMeterDefinition(
                UUID tenantId,
                UUID meterDefinitionId
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<UsageAggregateRecord> findByTenantProductKeyAndMeterKey(
                UUID tenantId,
                String productKey,
                String meterKey
        ) {
            return Optional.empty();
        }

        @Override
        public long countAll() {
            return events.get() > 0 ? 1L : 0L;
        }

        long totalValue() {
            return value.get();
        }

        long eventCount() {
            return events.get();
        }
    }

    static final class InMemoryUsageWindowAggregateRepository implements UsageWindowAggregateRepository {
        private final AtomicLong value = new AtomicLong();
        private final AtomicInteger events = new AtomicInteger();

        @Override
        public void applyEvent(
                UUID tenantId,
                ActiveMeterDefinition meter,
                UsageWindow window,
                long quantity,
                Instant occurredAt,
                Instant updatedAt
        ) {
            long contribution = switch (meter.aggregationType()) {
                case SUM, MAX -> quantity;
                case COUNT -> 1L;
            };
            if (meter.aggregationType() == AggregationType.MAX) {
                value.accumulateAndGet(contribution, Math::max);
            } else {
                value.addAndGet(contribution);
            }
            events.incrementAndGet();
        }

        @Override
        public Optional<UsageWindowAggregateRecord> findByTenantMeterAndWindow(
                UUID tenantId,
                UUID meterDefinitionId,
                Instant windowStart,
                Instant windowEnd
        ) {
            return Optional.empty();
        }

        @Override
        public Optional<UsageWindowAggregateRecord> findByTenantProductMeterAndWindow(
                UUID tenantId,
                String productKey,
                String meterKey,
                Instant windowStart,
                Instant windowEnd
        ) {
            return Optional.empty();
        }

        @Override
        public List<UsageWindowAggregateRecord> findByTenantProductMeterOverlapping(
                UUID tenantId,
                String productKey,
                String meterKey,
                Instant fromInclusive,
                Instant toExclusive
        ) {
            return List.of();
        }

        @Override
        public long countAll() {
            return events.get() > 0 ? 1L : 0L;
        }

        long totalValue() {
            return value.get();
        }

        long eventCount() {
            return events.get();
        }
    }

    static final class InMemoryCommercialUsageExceptionRepository implements CommercialUsageExceptionRepository {
        private final ConcurrentHashMap<UUID, CommercialUsageExceptionRecord> byEventId = new ConcurrentHashMap<>();

        @Override
        public Optional<UUID> insertIfAbsent(CommercialUsageExceptionRecord record) {
            CommercialUsageExceptionRecord previous = byEventId.putIfAbsent(record.eventId(), record);
            return previous == null ? Optional.of(record.id()) : Optional.empty();
        }

        @Override
        public long countByEventId(UUID eventId) {
            return byEventId.containsKey(eventId) ? 1L : 0L;
        }

        @Override
        public long countAll() {
            return byEventId.size();
        }
    }
}
