package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PostgreSQL-backed proofs for Phase 5B inbox + ledger idempotency.
 * Invokes the application processor directly (DB uniqueness is the concurrency authority).
 */
class IdempotentConsumerPersistenceIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final UUID EVENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @DynamicPropertySource
    static void persistenceConsumerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-persistence-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private UsageLedgerRepository usageLedgerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void singleEvent_persistsInboxAndLedgerFields() {
        usageReceivedProcessor.process(sampleEvent(EVENT_ID, "export-job-db-1"));

        assertThat(countProcessed(EVENT_ID)).isEqualTo(1);
        assertThat(usageLedgerRepository.countByEventId(EVENT_ID)).isEqualTo(1);

        var ledger = usageLedgerRepository.findByEventId(EVENT_ID).orElseThrow();
        assertThat(ledger.eventId()).isEqualTo(EVENT_ID);
        assertThat(ledger.tenantId()).isEqualTo(TENANT);
        assertThat(ledger.productKey()).isEqualTo("datapilot-cloud");
        assertThat(ledger.meterKey()).isEqualTo("scheduled_export");
        assertThat(ledger.quantity()).isEqualTo(5L);
        assertThat(ledger.occurredAt()).isEqualTo(OCCURRED);
        assertThat(ledger.idempotencyKey()).isEqualTo("export-job-db-1");
        assertThat(ledger.correlationId()).isEqualTo("corr-db-1");
        assertThat(ledger.principalId()).isEqualTo("svc-datapilot");
    }

    @Test
    void oneHundredDuplicateDeliveries_oneInboxAndOneLedger() {
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-db-100");

        for (int i = 0; i < 100; i++) {
            usageReceivedProcessor.process(event);
        }

        assertThat(countProcessedAll()).isEqualTo(1);
        assertThat(usageLedgerRepository.countAll()).isEqualTo(1);
        assertThat(countProcessed(EVENT_ID)).isEqualTo(1);
        assertThat(countLedger(EVENT_ID)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateProcessing_oneInboxAndOneLedger() throws Exception {
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-db-concurrent");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                usageReceivedProcessor.process(event);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(countProcessedAll()).isEqualTo(1);
        assertThat(usageLedgerRepository.countAll()).isEqualTo(1);
        assertThat(countProcessed(EVENT_ID)).isEqualTo(1);
        assertThat(countLedger(EVENT_ID)).isEqualTo(1);
    }

    @Test
    void simulatedRedeliveryAfterDbSuccess_keepsSingleLedger() {
        EventEnvelope<UsageReceivedPayload> event = sampleEvent(EVENT_ID, "export-job-db-redeliver");

        usageReceivedProcessor.process(event);
        assertThat(usageLedgerRepository.countAll()).isEqualTo(1);

        // Simulated redelivery after DB success (not a literal broker/process crash).
        usageReceivedProcessor.process(event);

        assertThat(countProcessedAll()).isEqualTo(1);
        assertThat(usageLedgerRepository.countAll()).isEqualTo(1);
    }

    @Test
    void duplicateDetectionUsesEventId_notIdempotencyKey() {
        UUID eventA = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID eventB = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        usageReceivedProcessor.process(sampleEvent(eventA, "same-http-key"));
        usageReceivedProcessor.process(sampleEvent(eventB, "same-http-key"));

        assertThat(countProcessedAll()).isEqualTo(2);
        assertThat(usageLedgerRepository.countAll()).isEqualTo(2);
    }

    private long countProcessed(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private long countProcessedAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Long.class);
        return count == null ? 0L : count;
    }

    private long countLedger(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private static EventEnvelope<UsageReceivedPayload> sampleEvent(UUID eventId, String idempotencyKey) {
        return new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                TENANT,
                UsagePartitionKey.of(TENANT, "datapilot-cloud", "scheduled_export"),
                "corr-db-1",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "scheduled_export",
                        5L,
                        idempotencyKey,
                        "svc-datapilot"
                )
        );
    }
}
