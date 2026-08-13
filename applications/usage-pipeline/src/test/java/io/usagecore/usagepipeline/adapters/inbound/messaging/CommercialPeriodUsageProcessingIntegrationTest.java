package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.IdempotentUsageReceivedProcessor;
import io.usagecore.usagepipeline.support.CommercialPeriodFixtureSeeder;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 7: commercial period enforcement on async UsageReceived processing.
 */
class CommercialPeriodUsageProcessingIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-20T12:00:00Z");
    private static final Instant PROCESSED = Instant.parse("2026-09-05T12:00:00Z");

    @DynamicPropertySource
    static void disableListener(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "phase7-period-processing");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdempotentUsageReceivedProcessor processor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private QuotaCommercialFixtureSeeder commercial;
    private CommercialPeriodFixtureSeeder periods;
    private UUID productId;

    @BeforeEach
    void setUp() {
        periods = new CommercialPeriodFixtureSeeder(jdbc);
        periods.clearCommercialTables();
        jdbc.update("DELETE FROM usage_window_aggregate");
        jdbc.update("DELETE FROM usage_aggregate");
        jdbc.update("DELETE FROM usage_ledger");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("DELETE FROM contract_version");
        jdbc.update("DELETE FROM contract");

        commercial = new QuotaCommercialFixtureSeeder(jdbc);
        commercial.ensureTenant(ACME, "acme-period");
        commercial.ensureTenant(GLOBEX, "globex-period");
        productId = commercial.ensureCatalogue();
    }

    @Test
    void noPeriod_preservesPhase6Aggregation() {
        UUID eventId = UUID.randomUUID();
        processor.process(event(eventId, ACME, 5L, "no-period-1", OCCURRED, PROCESSED));

        assertThat(countProcessed(eventId)).isEqualTo(1);
        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCount()).isZero();
        assertThat(lifetimeValue(ACME)).isEqualTo(5L);
        assertThat(windowValue(ACME)).isEqualTo(5L);
    }

    @Test
    void openPeriod_aggregatesNormally() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "OPEN");
        UUID eventId = UUID.randomUUID();
        processor.process(event(eventId, ACME, 7L, "open-1", OCCURRED, PROCESSED));

        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCount()).isZero();
        assertThat(lifetimeValue(ACME)).isEqualTo(7L);
        assertThat(windowValue(ACME)).isEqualTo(7L);
    }

    @Test
    void closingPeriod_asyncUsageStillAggregates() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "CLOSING");
        UUID eventId = UUID.randomUUID();
        processor.process(event(eventId, ACME, 4L, "closing-1", OCCURRED, PROCESSED));

        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCount()).isZero();
        assertThat(lifetimeValue(ACME)).isEqualTo(4L);
        assertThat(windowValue(ACME)).isEqualTo(4L);
    }

    @Test
    void reconcilingPeriod_quarantinesWithoutAggregateMutation() {
        UUID periodId = periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "RECONCILING");
        UUID eventId = UUID.randomUUID();
        processor.process(event(eventId, ACME, 9L, "reconciling-1", OCCURRED, PROCESSED));

        assertThat(countProcessed(eventId)).isEqualTo(1);
        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCountForEvent(eventId)).isEqualTo(1);
        assertThat(exceptionReason(eventId)).isEqualTo("PERIOD_RECONCILING");
        assertThat(lifetimeValue(ACME)).isZero();
        assertThat(windowValue(ACME)).isZero();
        assertThat(periods.periodStatus(periodId)).isEqualTo("RECONCILING");
    }

    @Test
    void finalizedLateEvent_quarantinesWithoutAggregateMutation() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "FINALIZED");
        UUID eventId = UUID.randomUUID();
        processor.process(event(eventId, ACME, 11L, "finalized-late-1", OCCURRED, PROCESSED));

        assertThat(countProcessed(eventId)).isEqualTo(1);
        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCountForEvent(eventId)).isEqualTo(1);
        assertThat(exceptionReason(eventId)).isEqualTo("PERIOD_FINALIZED");
        assertThat(lifetimeValue(ACME)).isZero();
        assertThat(windowValue(ACME)).isZero();
    }

    @Test
    void finalizedEvent_duplicateHundred_oneLedgerOneException() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "FINALIZED");
        UUID eventId = UUID.randomUUID();
        EventEnvelope<UsageReceivedPayload> envelope =
                event(eventId, ACME, 3L, "finalized-dup", OCCURRED, PROCESSED);

        for (int i = 0; i < 100; i++) {
            processor.process(envelope);
        }

        assertThat(countProcessed(eventId)).isEqualTo(1);
        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCountForEvent(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCount()).isEqualTo(1);
        assertThat(lifetimeValue(ACME)).isZero();
        assertThat(windowValue(ACME)).isZero();
    }

    @Test
    void tenantIsolation_finalizingAcmeDoesNotAffectGlobex() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "FINALIZED");
        periods.insertPeriod(GLOBEX, productId, AUG_START, SEP_START, "OPEN");

        processor.process(event(UUID.randomUUID(), ACME, 5L, "iso-acme", OCCURRED, PROCESSED));
        processor.process(event(UUID.randomUUID(), GLOBEX, 8L, "iso-globex", OCCURRED, PROCESSED));

        assertThat(lifetimeValue(ACME)).isZero();
        assertThat(lifetimeValue(GLOBEX)).isEqualTo(8L);
        assertThat(windowValue(GLOBEX)).isEqualTo(8L);
        assertThat(periods.exceptionCount()).isEqualTo(1);
    }

    @Test
    void periodBoundaryResolution_startInclusiveEndExclusive() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "OPEN");

        processor.process(event(UUID.randomUUID(), ACME, 1L, "bound-start", AUG_START, PROCESSED));
        processor.process(event(
                UUID.randomUUID(),
                ACME,
                2L,
                "bound-end",
                SEP_START,
                PROCESSED
        ));

        // start inclusive → under OPEN period → aggregated
        // end exclusive → NO_PERIOD (no September period) → also aggregated (compat)
        assertThat(lifetimeValue(ACME)).isEqualTo(3L);
        assertThat(periods.exceptionCount()).isZero();
    }

    @Test
    void finalizationRace_serializedByPostgres_noAggregateMutation() throws Exception {
        UUID periodId = periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "RECONCILING");
        UUID eventId = UUID.randomUUID();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> usageError = new AtomicReference<>();
        AtomicReference<Throwable> finalizeError = new AtomicReference<>();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> usageFuture = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    processor.process(event(eventId, ACME, 13L, "race-usage", OCCURRED, PROCESSED));
                } catch (Throwable t) {
                    usageError.set(t);
                }
            });
            Future<?> finalizeFuture = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    tx.executeWithoutResult(status -> {
                        int updated = jdbc.update(
                                """
                                UPDATE commercial_period
                                SET status = 'FINALIZED',
                                    finalized_at = ?,
                                    finalized_by = 'race-finalizer',
                                    updated_at = ?
                                WHERE id = ?
                                  AND status = 'RECONCILING'
                                """,
                                java.sql.Timestamp.from(Instant.parse("2026-09-03T12:00:00Z")),
                                java.sql.Timestamp.from(Instant.parse("2026-09-03T12:00:00Z")),
                                periodId
                        );
                        assertThat(updated).isEqualTo(1);
                    });
                } catch (Throwable t) {
                    finalizeError.set(t);
                }
            });

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            usageFuture.get(30, TimeUnit.SECONDS);
            finalizeFuture.get(30, TimeUnit.SECONDS);
        }

        assertThat(usageError.get()).isNull();
        assertThat(finalizeError.get()).isNull();
        assertThat(periods.periodStatus(periodId)).isEqualTo("FINALIZED");
        assertThat(countLedger(eventId)).isEqualTo(1);
        assertThat(periods.exceptionCountForEvent(eventId)).isEqualTo(1);
        assertThat(lifetimeValue(ACME)).isZero();
        assertThat(windowValue(ACME)).isZero();
        String reason = exceptionReason(eventId);
        assertThat(reason).isIn("PERIOD_RECONCILING", "PERIOD_FINALIZED");
    }

    /**
     * Flagship CLOSING → RECONCILING race against in-flight UsageReceived mutation.
     * <p>
     * PostgreSQL {@code FOR SHARE} (usage) vs conditional {@code UPDATE} (transition)
     * must serialize so stale CLOSING aggregate mutation cannot commit after RECONCILING.
     * <p>
     * Allowed winners:
     * <ul>
     *   <li>usage observes CLOSING → ledger + aggregates, then transition commits</li>
     *   <li>transition commits first → usage observes RECONCILING → ledger + exception, no aggregates</li>
     * </ul>
     * Forbidden: aggregates and commercial_usage_exception together (inconsistent / stale path).
     */
    @Test
    void closingToReconcilingRace_serializedByPostgres_noStaleAggregateMutation() throws Exception {
        UUID periodId = periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "CLOSING");
        UUID eventId = UUID.randomUUID();
        long quantity = 13L;

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> usageError = new AtomicReference<>();
        AtomicReference<Throwable> transitionError = new AtomicReference<>();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> usageFuture = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    processor.process(event(eventId, ACME, quantity, "closing-reconciling-race", OCCURRED, PROCESSED));
                } catch (Throwable t) {
                    usageError.set(t);
                }
            });
            Future<?> transitionFuture = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    tx.executeWithoutResult(status -> {
                        int updated = jdbc.update(
                                """
                                UPDATE commercial_period
                                SET status = 'RECONCILING',
                                    reconciling_started_at = ?,
                                    updated_at = ?
                                WHERE id = ?
                                  AND status = 'CLOSING'
                                """,
                                java.sql.Timestamp.from(Instant.parse("2026-09-02T12:00:00Z")),
                                java.sql.Timestamp.from(Instant.parse("2026-09-02T12:00:00Z")),
                                periodId
                        );
                        assertThat(updated).isEqualTo(1);
                    });
                } catch (Throwable t) {
                    transitionError.set(t);
                }
            });

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            usageFuture.get(30, TimeUnit.SECONDS);
            transitionFuture.get(30, TimeUnit.SECONDS);
        }

        assertThat(usageError.get()).as("usage thread error").isNull();
        assertThat(transitionError.get()).as("transition thread error").isNull();
        assertThat(periods.periodStatus(periodId)).isEqualTo("RECONCILING");
        assertThat(countProcessed(eventId)).isEqualTo(1);
        assertThat(countLedger(eventId)).isEqualTo(1);

        long lifetime = lifetimeValue(ACME);
        long window = windowValue(ACME);
        long exceptions = periods.exceptionCountForEvent(eventId);
        boolean aggregated = lifetime == quantity && window == quantity;
        boolean quarantined = exceptions == 1L;

        // Exactly one coherent winner — never both, never neither.
        assertThat(aggregated ^ quarantined)
                .as(
                        "expected XOR(aggregated, quarantined) but lifetime=%s window=%s exceptions=%s",
                        lifetime,
                        window,
                        exceptions
                )
                .isTrue();

        if (quarantined) {
            assertThat(lifetime).isZero();
            assertThat(window).isZero();
            assertThat(exceptionReason(eventId)).isEqualTo("PERIOD_RECONCILING");
            assertThat(periods.exceptionCount()).isEqualTo(1);
        } else {
            assertThat(lifetime).isEqualTo(quantity);
            assertThat(window).isEqualTo(quantity);
            assertThat(exceptions).isZero();
            assertThat(periods.exceptionCount()).isZero();
        }
    }

    private EventEnvelope<UsageReceivedPayload> event(
            UUID eventId,
            UUID tenantId,
            long quantity,
            String idempotencyKey,
            Instant occurredAt,
            Instant producedAt
    ) {
        return new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                occurredAt,
                tenantId,
                tenantId + "|" + MeterDefinitionFixtureSeeder.PRODUCT_KEY + "|"
                        + MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "corr-" + idempotencyKey,
                null,
                null,
                producedAt,
                new UsageReceivedPayload(
                        MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                        MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                        quantity,
                        idempotencyKey,
                        "svc-test"
                )
        );
    }

    private long countProcessed(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private long countLedger(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private long lifetimeValue(UUID tenantId) {
        Long value = jdbc.query(
                """
                SELECT ua.aggregate_value
                FROM usage_aggregate ua
                INNER JOIN meter_definition md ON md.id = ua.meter_definition_id
                INNER JOIN product p ON p.id = md.product_id
                WHERE ua.tenant_id = ? AND p.product_key = ? AND md.meter_key = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : 0L,
                tenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS
        );
        return value == null ? 0L : value;
    }

    private long windowValue(UUID tenantId) {
        Long value = jdbc.query(
                """
                SELECT uwa.aggregate_value
                FROM usage_window_aggregate uwa
                INNER JOIN meter_definition md ON md.id = uwa.meter_definition_id
                INNER JOIN product p ON p.id = md.product_id
                WHERE uwa.tenant_id = ?
                  AND p.product_key = ?
                  AND md.meter_key = ?
                  AND uwa.window_start = ?
                  AND uwa.window_end = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : 0L,
                tenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                java.sql.Timestamp.from(AUG_START),
                java.sql.Timestamp.from(SEP_START)
        );
        return value == null ? 0L : value;
    }

    private String exceptionReason(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT reason FROM commercial_usage_exception WHERE event_id = ?",
                String.class,
                eventId
        );
    }
}
