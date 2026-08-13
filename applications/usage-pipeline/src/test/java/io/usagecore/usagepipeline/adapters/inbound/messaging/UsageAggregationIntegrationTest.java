package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UnknownUsageMeterException;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRepository;
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
 * Phase 6A flagship evidence: SUM/COUNT/MAX semantics, duplicate safety, concurrency,
 * atomicity with aggregate failure, unknown meter, tenant isolation, out-of-order event time.
 */
class UsageAggregationIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @DynamicPropertySource
    static void aggregationConsumerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-aggregation-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private UsageAggregateRepository usageAggregateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MeterDefinitionFixtureSeeder meters;

    @BeforeEach
    void cleanAndSeed() {
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
        meters = new MeterDefinitionFixtureSeeder(jdbcTemplate);
        meters.ensureDataPilotProductAndMeters();
    }

    @Test
    void sumSemantics_tenPlusTwentyFivePlusFive_equalsForty() {
        process(ACME, "api_requests", 10L, "sum-1", Instant.parse("2026-08-12T10:00:00Z"));
        process(ACME, "api_requests", 25L, "sum-2", Instant.parse("2026-08-12T11:00:00Z"));
        process(ACME, "api_requests", 5L, "sum-3", Instant.parse("2026-08-12T12:00:00Z"));

        UsageAggregateRecord aggregate = requireAggregate(ACME, "api_requests");
        assertThat(aggregate.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(aggregate.aggregateValue()).isEqualTo(40L);
        assertThat(aggregate.eventCount()).isEqualTo(3L);
        assertThat(countLedgerAll()).isEqualTo(3);
        assertThat(countProcessedAll()).isEqualTo(3);
    }

    @Test
    void countSemantics_threeEvents_ignoreQuantity() {
        process(ACME, "scheduled_export", 100L, "count-1", Instant.parse("2026-08-12T10:00:00Z"));
        process(ACME, "scheduled_export", 30L, "count-2", Instant.parse("2026-08-12T11:00:00Z"));
        process(ACME, "scheduled_export", 900L, "count-3", Instant.parse("2026-08-12T12:00:00Z"));

        UsageAggregateRecord aggregate = requireAggregate(ACME, "scheduled_export");
        assertThat(aggregate.aggregationType()).isEqualTo(AggregationType.COUNT);
        assertThat(aggregate.aggregateValue()).isEqualTo(3L);
        assertThat(aggregate.eventCount()).isEqualTo(3L);
    }

    @Test
    void maxSemantics_tenTwentyFiveFive_equalsTwentyFive() {
        process(ACME, "workspace_size", 10L, "max-1", Instant.parse("2026-08-12T10:00:00Z"));
        process(ACME, "workspace_size", 25L, "max-2", Instant.parse("2026-08-12T11:00:00Z"));
        process(ACME, "workspace_size", 5L, "max-3", Instant.parse("2026-08-12T12:00:00Z"));

        UsageAggregateRecord aggregate = requireAggregate(ACME, "workspace_size");
        assertThat(aggregate.aggregationType()).isEqualTo(AggregationType.MAX);
        assertThat(aggregate.aggregateValue()).isEqualTo(25L);
        assertThat(aggregate.eventCount()).isEqualTo(3L);
    }

    @Test
    void duplicateStorm_sameEventIdTimes100_aggregateAffectedOnce() {
        UUID eventId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        EventEnvelope<UsageReceivedPayload> event = event(
                eventId,
                ACME,
                "api_requests",
                10L,
                "dup-storm",
                Instant.parse("2026-08-12T10:00:00Z")
        );

        for (int i = 0; i < 100; i++) {
            usageReceivedProcessor.process(event);
        }

        assertThat(countProcessedAll()).isEqualTo(1);
        assertThat(countLedgerAll()).isEqualTo(1);
        UsageAggregateRecord aggregate = requireAggregate(ACME, "api_requests");
        assertThat(aggregate.aggregateValue()).isEqualTo(10L);
        assertThat(aggregate.eventCount()).isEqualTo(1L);
    }

    @Test
    void concurrentDistinctEvents_oneHundredQuantityOne_sumEquals100() throws Exception {
        int events = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                process(
                        ACME,
                        "api_requests",
                        1L,
                        "concurrent-distinct-" + index,
                        Instant.parse("2026-08-12T10:00:00Z").plusSeconds(index)
                );
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(countProcessedAll()).isEqualTo(100);
        assertThat(countLedgerAll()).isEqualTo(100);
        UsageAggregateRecord aggregate = requireAggregate(ACME, "api_requests");
        assertThat(aggregate.aggregateValue()).isEqualTo(100L);
        assertThat(aggregate.eventCount()).isEqualTo(100L);
    }

    @Test
    void concurrentDuplicateStorm_oneInboxLedgerAndAggregateEffect() throws Exception {
        UUID eventId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        EventEnvelope<UsageReceivedPayload> event = event(
                eventId,
                ACME,
                "api_requests",
                7L,
                "concurrent-dup",
                Instant.parse("2026-08-12T10:00:00Z")
        );

        int threads = 40;
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
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(countProcessedAll()).isEqualTo(1);
        assertThat(countLedgerAll()).isEqualTo(1);
        UsageAggregateRecord aggregate = requireAggregate(ACME, "api_requests");
        assertThat(aggregate.aggregateValue()).isEqualTo(7L);
        assertThat(aggregate.eventCount()).isEqualTo(1L);
    }

    @Test
    void unknownMeter_noInboxLedgerOrAggregate() {
        assertThatThrownBy(() -> process(
                ACME,
                "does_not_exist",
                1L,
                "unknown-meter",
                Instant.parse("2026-08-12T10:00:00Z")
        ))
                .isInstanceOf(UnknownUsageMeterException.class);

        assertThat(countProcessedAll()).isZero();
        assertThat(countLedgerAll()).isZero();
        assertThat(usageAggregateRepository.countAll()).isZero();
    }

    @Test
    void inactiveMeter_noInboxLedgerOrAggregate() {
        meters.ensureInactiveMeter("retired_meter");

        assertThatThrownBy(() -> process(
                ACME,
                "retired_meter",
                1L,
                "inactive-meter",
                Instant.parse("2026-08-12T10:00:00Z")
        ))
                .isInstanceOf(UnknownUsageMeterException.class);

        assertThat(countProcessedAll()).isZero();
        assertThat(countLedgerAll()).isZero();
        assertThat(usageAggregateRepository.countAll()).isZero();
    }

    @Test
    void tenantIsolation_acmeAndGlobexIndependent() {
        process(ACME, "api_requests", 100L, "acme-sum", Instant.parse("2026-08-12T10:00:00Z"));
        process(GLOBEX, "api_requests", 500L, "globex-sum", Instant.parse("2026-08-12T10:00:00Z"));

        assertThat(requireAggregate(ACME, "api_requests").aggregateValue()).isEqualTo(100L);
        assertThat(requireAggregate(GLOBEX, "api_requests").aggregateValue()).isEqualTo(500L);
    }

    @Test
    void outOfOrderOccurredAt_lastEventAtKeepsMaximum() {
        Instant noon = Instant.parse("2026-08-12T12:00:00Z");
        Instant tenAm = Instant.parse("2026-08-12T10:00:00Z");

        process(ACME, "api_requests", 1L, "later-first", noon);
        process(ACME, "api_requests", 1L, "earlier-second", tenAm);

        UsageAggregateRecord aggregate = requireAggregate(ACME, "api_requests");
        assertThat(aggregate.lastEventAt()).isEqualTo(noon);
        assertThat(aggregate.aggregateValue()).isEqualTo(2L);
        assertThat(aggregate.eventCount()).isEqualTo(2L);
    }

    private void process(UUID tenantId, String meterKey, long quantity, String idempotencyKey, Instant occurredAt) {
        usageReceivedProcessor.process(event(
                UUID.randomUUID(),
                tenantId,
                meterKey,
                quantity,
                idempotencyKey,
                occurredAt
        ));
    }

    private static EventEnvelope<UsageReceivedPayload> event(
            UUID eventId,
            UUID tenantId,
            String meterKey,
            long quantity,
            String idempotencyKey,
            Instant occurredAt
    ) {
        return new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                occurredAt,
                tenantId,
                UsagePartitionKey.of(tenantId, "datapilot-cloud", meterKey),
                "corr-agg",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        meterKey,
                        quantity,
                        idempotencyKey,
                        "svc-datapilot"
                )
        );
    }

    private UsageAggregateRecord requireAggregate(UUID tenantId, String meterKey) {
        return usageAggregateRepository
                .findByTenantProductKeyAndMeterKey(tenantId, "datapilot-cloud", meterKey)
                .orElseThrow();
    }

    private long countProcessedAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Long.class);
        return count == null ? 0L : count;
    }

    private long countLedgerAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_ledger", Long.class);
        return count == null ? 0L : count;
    }
}
