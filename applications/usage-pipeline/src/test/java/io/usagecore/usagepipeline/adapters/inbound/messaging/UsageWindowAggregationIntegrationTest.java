package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UnknownUsageMeterException;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
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
 * Phase 6B flagship evidence: event-time MONTHLY windows, SUM/COUNT/MAX,
 * out-of-order, duplicates, concurrency across windows, tenant isolation.
 * Uses FixedClock (2026-08-12) so August events are not late.
 */
class UsageWindowAggregationIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCT_START = Instant.parse("2026-10-01T00:00:00Z");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-window-agg-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private UsageWindowAggregateRepository usageWindowAggregateRepository;

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
    void sumWindow_tenPlusTwentyFivePlusFive_equalsFortyInAugust() {
        process(ACME, "api_requests", 10L, "w-sum-1", Instant.parse("2026-08-12T10:00:00Z"));
        process(ACME, "api_requests", 25L, "w-sum-2", Instant.parse("2026-08-20T11:00:00Z"));
        process(ACME, "api_requests", 5L, "w-sum-3", Instant.parse("2026-08-28T12:00:00Z"));

        UsageWindowAggregateRecord august = requireWindow(ACME, "api_requests", AUG_START, SEP_START);
        assertThat(august.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(august.aggregateValue()).isEqualTo(40L);
        assertThat(august.eventCount()).isEqualTo(3L);
        assertThat(countWindows()).isEqualTo(1);
    }

    @Test
    void countWindow_threeEvents_ignoreQuantity() {
        process(ACME, "scheduled_export", 100L, "w-count-1", Instant.parse("2026-08-12T10:00:00Z"));
        process(ACME, "scheduled_export", 30L, "w-count-2", Instant.parse("2026-08-13T11:00:00Z"));
        process(ACME, "scheduled_export", 900L, "w-count-3", Instant.parse("2026-08-14T12:00:00Z"));

        UsageWindowAggregateRecord august = requireWindow(ACME, "scheduled_export", AUG_START, SEP_START);
        assertThat(august.aggregationType()).isEqualTo(AggregationType.COUNT);
        assertThat(august.aggregateValue()).isEqualTo(3L);
        assertThat(august.eventCount()).isEqualTo(3L);
    }

    @Test
    void maxWindow_tenTwentyFiveFive_equalsTwentyFive() {
        process(ACME, "workspace_size", 10L, "w-max-1", Instant.parse("2026-08-12T10:00:00Z"));
        process(ACME, "workspace_size", 25L, "w-max-2", Instant.parse("2026-08-13T11:00:00Z"));
        process(ACME, "workspace_size", 5L, "w-max-3", Instant.parse("2026-08-14T12:00:00Z"));

        UsageWindowAggregateRecord august = requireWindow(ACME, "workspace_size", AUG_START, SEP_START);
        assertThat(august.aggregationType()).isEqualTo(AggregationType.MAX);
        assertThat(august.aggregateValue()).isEqualTo(25L);
        assertThat(august.eventCount()).isEqualTo(3L);
    }

    @Test
    void outOfOrderOccurredAt_sameAugustWindowSumEquals22() {
        process(ACME, "api_requests", 10L, "ooo-25", Instant.parse("2026-08-25T10:00:00Z"));
        process(ACME, "api_requests", 5L, "ooo-10", Instant.parse("2026-08-10T10:00:00Z"));
        process(ACME, "api_requests", 7L, "ooo-20", Instant.parse("2026-08-20T10:00:00Z"));

        UsageWindowAggregateRecord august = requireWindow(ACME, "api_requests", AUG_START, SEP_START);
        assertThat(august.aggregateValue()).isEqualTo(22L);
        assertThat(august.eventCount()).isEqualTo(3L);
        assertThat(august.firstEventAt()).isEqualTo(Instant.parse("2026-08-10T10:00:00Z"));
        assertThat(august.lastEventAt()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
    }

    @Test
    void monthlyBoundary_septemberStartGoesToSeptemberNotAugust() {
        process(ACME, "api_requests", 3L, "boundary-aug-start", Instant.parse("2026-08-01T00:00:00Z"));
        process(ACME, "api_requests", 5L, "boundary-aug-end", Instant.parse("2026-08-31T23:59:59Z"));
        process(ACME, "api_requests", 7L, "boundary-sep-start", Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(requireWindow(ACME, "api_requests", AUG_START, SEP_START).aggregateValue()).isEqualTo(8L);
        assertThat(requireWindow(ACME, "api_requests", SEP_START, OCT_START).aggregateValue()).isEqualTo(7L);
        assertThat(countWindows()).isEqualTo(2);
    }

    @Test
    void duplicateStorm_sameEventIdTimes100_windowAffectedOnce() {
        UUID eventId = UUID.fromString("dddddddd-eeee-ffff-aaaa-bbbbbbbbbbbb");
        EventEnvelope<UsageReceivedPayload> event = event(
                eventId,
                ACME,
                "api_requests",
                10L,
                "w-dup-storm",
                Instant.parse("2026-08-12T10:00:00Z")
        );
        for (int i = 0; i < 100; i++) {
            usageReceivedProcessor.process(event);
        }

        assertThat(countLedger()).isEqualTo(1);
        UsageWindowAggregateRecord august = requireWindow(ACME, "api_requests", AUG_START, SEP_START);
        assertThat(august.aggregateValue()).isEqualTo(10L);
        assertThat(august.eventCount()).isEqualTo(1L);
        assertThat(isLate(eventId)).isFalse();
    }

    @Test
    void concurrentDistinctEvents_sameAugustWindow_sumEquals100() throws Exception {
        runConcurrent(100, index -> process(
                ACME,
                "api_requests",
                1L,
                "w-concurrent-same-" + index,
                Instant.parse("2026-08-12T10:00:00Z").plusSeconds(index)
        ));

        UsageWindowAggregateRecord august = requireWindow(ACME, "api_requests", AUG_START, SEP_START);
        assertThat(august.aggregateValue()).isEqualTo(100L);
        assertThat(august.eventCount()).isEqualTo(100L);
        assertThat(countWindows()).isEqualTo(1);
    }

    @Test
    void concurrentEvents_fiftyAugustFiftySeptember_noCrossWindowMixing() throws Exception {
        runConcurrent(100, index -> {
            Instant occurred = index < 50
                    ? Instant.parse("2026-08-15T10:00:00Z").plusSeconds(index)
                    : Instant.parse("2026-09-02T10:00:00Z").plusSeconds(index - 50);
            process(ACME, "api_requests", 1L, "w-cross-window-" + index, occurred);
        });

        assertThat(requireWindow(ACME, "api_requests", AUG_START, SEP_START).aggregateValue()).isEqualTo(50L);
        assertThat(requireWindow(ACME, "api_requests", SEP_START, OCT_START).aggregateValue()).isEqualTo(50L);
        assertThat(countWindows()).isEqualTo(2);
    }

    @Test
    void tenantIsolation_acmeAndGlobexIndependentWindows() {
        process(ACME, "api_requests", 100L, "w-acme", Instant.parse("2026-08-12T10:00:00Z"));
        process(GLOBEX, "api_requests", 500L, "w-globex", Instant.parse("2026-08-12T10:00:00Z"));

        assertThat(requireWindow(ACME, "api_requests", AUG_START, SEP_START).aggregateValue()).isEqualTo(100L);
        assertThat(requireWindow(GLOBEX, "api_requests", AUG_START, SEP_START).aggregateValue()).isEqualTo(500L);
    }

    @Test
    void unknownMeter_noWindowState() {
        assertThatThrownBy(() -> process(
                ACME,
                "does_not_exist",
                1L,
                "w-unknown",
                Instant.parse("2026-08-12T10:00:00Z")
        )).isInstanceOf(UnknownUsageMeterException.class);

        assertThat(countLedger()).isZero();
        assertThat(countWindows()).isZero();
        assertThat(usageWindowAggregateRepository.countAll()).isZero();
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
                "corr-window",
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

    private UsageWindowAggregateRecord requireWindow(
            UUID tenantId,
            String meterKey,
            Instant windowStart,
            Instant windowEnd
    ) {
        return usageWindowAggregateRepository
                .findByTenantProductMeterAndWindow(tenantId, "datapilot-cloud", meterKey, windowStart, windowEnd)
                .orElseThrow();
    }

    private long countWindows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_window_aggregate", Long.class);
        return count == null ? 0L : count;
    }

    private long countLedger() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_ledger", Long.class);
        return count == null ? 0L : count;
    }

    private boolean isLate(UUID eventId) {
        Boolean late = jdbcTemplate.queryForObject(
                "SELECT is_late FROM usage_ledger WHERE event_id = ?",
                Boolean.class,
                eventId
        );
        return Boolean.TRUE.equals(late);
    }

    private void runConcurrent(int events, ConcurrentAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                action.run(index);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    @FunctionalInterface
    private interface ConcurrentAction {
        void run(int index) throws Exception;
    }
}
