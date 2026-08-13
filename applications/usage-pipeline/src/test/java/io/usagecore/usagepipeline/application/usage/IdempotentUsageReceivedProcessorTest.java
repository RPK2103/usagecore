package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
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
import org.junit.jupiter.api.Test;

class IdempotentUsageReceivedProcessorTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final Instant FIXED = Instant.parse("2026-08-12T14:31:00Z");
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void singleEvent_writesOneProcessedEventAndOneLedgerRow() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);

        processor.process(sampleEvent(EVENT_ID, "export-job-1"));

        assertThat(inbox.countByEventId(EVENT_ID)).isEqualTo(1);
        assertThat(ledger.countByEventId(EVENT_ID)).isEqualTo(1);
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
    }

    @Test
    void oneHundredDuplicateDeliveries_produceOneBusinessEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-100");

        for (int i = 0; i < 100; i++) {
            processor.process(event);
        }

        assertThat(inbox.countAll()).isEqualTo(1);
        assertThat(ledger.countAll()).isEqualTo(1);
        assertThat(inbox.countByEventId(EVENT_ID)).isEqualTo(1);
        assertThat(ledger.countByEventId(EVENT_ID)).isEqualTo(1);
    }

    @Test
    void simulatedRedeliveryAfterDbSuccess_isSuccessfulNoOp() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-redeliver");

        processor.process(event);
        assertThat(ledger.countAll()).isEqualTo(1);

        // Simulated redelivery (not a literal process crash): same eventId after DB success.
        processor.process(event);

        assertThat(inbox.countAll()).isEqualTo(1);
        assertThat(ledger.countAll()).isEqualTo(1);
    }

    @Test
    void duplicateDetectionUsesEventId_notHttpIdempotencyKey() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);

        UUID eventA = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID eventB = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        processor.process(sampleEvent(eventA, "shared-http-key"));
        processor.process(sampleEvent(eventB, "shared-http-key"));

        assertThat(inbox.countAll()).isEqualTo(2);
        assertThat(ledger.countAll()).isEqualTo(2);
        assertThat(ledger.findByEventId(eventA).orElseThrow().idempotencyKey()).isEqualTo("shared-http-key");
        assertThat(ledger.findByEventId(eventB).orElseThrow().idempotencyKey()).isEqualTo("shared-http-key");
    }

    @Test
    void concurrentDuplicateProcessing_oneEffect() throws Exception {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-concurrent");

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
    }

    @Test
    void unsupportedEventVersion_isRejectedWithoutLedgerEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);

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
    }

    @Test
    void nonPositiveQuantity_isRejectedWithoutLedgerEffect() {
        InMemoryProcessedEventRepository inbox = new InMemoryProcessedEventRepository();
        InMemoryUsageLedgerRepository ledger = new InMemoryUsageLedgerRepository();
        IdempotentUsageReceivedProcessor processor = newProcessor(inbox, ledger);

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
    }

    private static IdempotentUsageReceivedProcessor newProcessor(
            ProcessedEventRepository inbox,
            UsageLedgerRepository ledger
    ) {
        return new IdempotentUsageReceivedProcessor(
                inbox,
                ledger,
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );
    }

    private static EventEnvelope<UsageReceivedPayload> sampleEvent(UUID eventId, String idempotencyKey) {
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
                        3L,
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
        private final AtomicInteger inserts = new AtomicInteger();

        @Override
        public void insert(UsageLedgerRecord record) {
            inserts.incrementAndGet();
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
}
