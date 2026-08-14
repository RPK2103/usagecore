package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.ReconciliationFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

class UsageAdjustmentApiIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MID_AUG = Instant.parse("2026-08-15T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ReconciliationFixtureSeeder fixtures;
    private UUID acmeTenantId;
    private UUID globexTenantId;
    private UUID productId;
    private UUID apiMeterId;
    private UUID exportMeterId;
    private UUID workspaceMeterId;

    @BeforeEach
    void setUp() {
        fixtures = new ReconciliationFixtureSeeder(jdbcTemplate);
        fixtures.clearUsageEvidence();
        acmeTenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        globexTenantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        fixtures.quota().ensureTenant(acmeTenantId, "acme");
        fixtures.quota().ensureTenant(globexTenantId, "globex");
        productId = fixtures.meters().ensureDataPilotProductAndMeters();
        apiMeterId = fixtures.meters().meterDefinitionId(MeterDefinitionFixtureSeeder.METER_API_REQUESTS);
        exportMeterId = fixtures.meters().meterDefinitionId(MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT);
        workspaceMeterId = fixtures.meters().meterDefinitionId(MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE);
    }

    @Test
    void flagship_finalizedSum_applyQuarantine_thenNewReconMatches() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 97, MID_AUG, applied);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG.plusSeconds(1), quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acmeTenantId, productId, apiMeterId, periodId, "PERIOD_FINALIZED", MID_AUG.plusSeconds(1)
        );
        seedSumState(acmeTenantId, 97, 1);

        String runA = startRecon(periodId);
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", runA)
                .then()
                .statusCode(200)
                .body("[0].commercialExpectedValue", equalTo(97))
                .body("[0].actualValue", equalTo(97))
                .body("[0].unresolvedExceptionCount", equalTo(1))
                .body("[0].adjustedEventCount", equalTo(0));

        String adjustmentId = apply(runA, exceptionId, "apply-late-event-174", "Approved after customer usage review")
                .then()
                .statusCode(200)
                .body("adjustmentType", equalTo("APPLY_QUARANTINED_USAGE"))
                .body("status", equalTo("APPLIED"))
                .body("aggregateValueContribution", equalTo(3))
                .body("eventCountContribution", equalTo(1))
                .body("meterKey", equalTo(MeterDefinitionFixtureSeeder.METER_API_REQUESTS))
                .extract()
                .path("adjustmentId");

        assertThat(fixtures.adjustmentCount()).isEqualTo(1);
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
        assertThat(fixtures.windowEventCount(acmeTenantId, apiMeterId)).isEqualTo(2L);
        assertThat(fixtures.lifetimeAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
        assertThat(fixtures.ledgerCountForEvent(quarantined)).isEqualTo(1);
        assertThat(fixtures.ledgerQuantity(quarantined)).isEqualTo(3L);
        assertThat(fixtures.exceptionCount()).isEqualTo(1);
        assertThat(fixtures.exceptionReason(exceptionId)).isEqualTo("PERIOD_FINALIZED");
        assertThat(fixtures.periods().periodStatus(periodId)).isEqualTo("FINALIZED");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", runA)
                .then()
                .statusCode(200)
                .body("[0].commercialExpectedValue", equalTo(97))
                .body("[0].actualValue", equalTo(97))
                .body("[0].adjustedEventCount", equalTo(0));

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/usage/adjustments/{id}", adjustmentId)
                .then()
                .statusCode(200)
                .body("adjustmentId", equalTo(adjustmentId))
                .body("status", equalTo("APPLIED"));

        String runB = startRecon(periodId);
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", runB)
                .then()
                .statusCode(200)
                .body("[0].commercialExpectedValue", equalTo(100))
                .body("[0].actualValue", equalTo(100))
                .body("[0].status", equalTo("MATCH"))
                .body("[0].adjustedEventCount", equalTo(1))
                .body("[0].unresolvedExceptionCount", equalTo(0))
                .body("[0].quarantinedEventCount", equalTo(1));
        assertThat(runB).isNotEqualTo(runA);
    }

    @Test
    void applyCount_contributesOneNotQuantity() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, 900, MID_AUG, quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acmeTenantId, productId, exportMeterId, periodId, "PERIOD_RECONCILING", MID_AUG
        );
        fixtures.insertWindowAggregate(
                acmeTenantId, productId, exportMeterId, MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT,
                "COUNT", WINDOW_START, WINDOW_END, 4, 4
        );
        fixtures.insertLifetimeAggregate(
                acmeTenantId, productId, exportMeterId, MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT,
                "COUNT", 4, 4
        );
        String runId = startRecon(periodId);
        apply(runId, exceptionId, "apply-count-900", "COUNT contribution is one event")
                .then()
                .statusCode(200)
                .body("aggregateValueContribution", equalTo(1))
                .body("eventCountContribution", equalTo(1));
        assertThat(fixtures.windowAggregateValue(acmeTenantId, exportMeterId)).isEqualTo(5L);
        assertThat(fixtures.windowEventCount(acmeTenantId, exportMeterId)).isEqualTo(5L);
    }

    @Test
    void applyMax_increasesWhenQuarantinedIsLarger() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        seedMax(periodId, 25, 40);
    }

    @Test
    void applyMax_doesNotDecreaseWhenExistingIsLarger() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, 50, MID_AUG, applied);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, 40, MID_AUG.plusSeconds(1), quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acmeTenantId, productId, workspaceMeterId, periodId, "PERIOD_FINALIZED", MID_AUG.plusSeconds(1)
        );
        fixtures.insertWindowAggregate(
                acmeTenantId, productId, workspaceMeterId, MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE,
                "MAX", WINDOW_START, WINDOW_END, 50, 1
        );
        fixtures.insertLifetimeAggregate(
                acmeTenantId, productId, workspaceMeterId, MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE,
                "MAX", 50, 1
        );
        String runId = startRecon(periodId);
        apply(runId, exceptionId, "apply-max-no-increase", "MAX already larger")
                .then()
                .statusCode(200)
                .body("aggregateValueContribution", equalTo(40));
        assertThat(fixtures.windowAggregateValue(acmeTenantId, workspaceMeterId)).isEqualTo(50L);
        assertThat(fixtures.windowEventCount(acmeTenantId, workspaceMeterId)).isEqualTo(2L);
        assertThat(fixtures.lifetimeAggregateValue(acmeTenantId, workspaceMeterId)).isEqualTo(50L);
        assertThat(fixtures.lifetimeEventCount(acmeTenantId, workspaceMeterId)).isEqualTo(2L);
    }

    @Test
    void openPeriod_rejected() {
        rejectPeriodStatus("OPEN");
    }

    @Test
    void closingPeriod_rejected() {
        rejectPeriodStatus("CLOSING");
    }

    @Test
    void reconcilingPeriod_allowed() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_RECONCILING");
        String runId = startRecon(periodId);
        apply(runId, seeded.exceptionId(), "apply-reconciling", "allowed")
                .then()
                .statusCode(200);
        assertThat(fixtures.periods().periodStatus(periodId)).isEqualTo("RECONCILING");
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
    }

    @Test
    void duplicateIdenticalRequest_oneHundredTimes_oneAdjustment() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String runId = startRecon(periodId);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = apply(runId, seeded.exceptionId(), "dup-100", "same reason")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("adjustmentId");
            ids.add(id);
        }
        assertThat(ids).hasSize(1);
        assertThat(fixtures.adjustmentCount()).isEqualTo(1);
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
        assertThat(fixtures.lifetimeAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
    }

    @Test
    void concurrentSameException_oneContribution() throws Exception {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String runId = startRecon(periodId);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return apply(runId, seeded.exceptionId(), "concurrent-same", "race")
                        .then()
                        .extract()
                        .statusCode();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> future : futures) {
            codes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(codes).hasSize(threads).allMatch(code -> code == 200);
        assertThat(fixtures.adjustmentCount()).isEqualTo(1);
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
        assertThat(fixtures.lifetimeAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
    }

    @Test
    void conflictingIdempotency_oneWins() throws Exception {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG, q1);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 5, MID_AUG.plusSeconds(1), q2);
        UUID ex1 = fixtures.insertException(q1, acmeTenantId, productId, apiMeterId, periodId, "PERIOD_FINALIZED", MID_AUG);
        UUID ex2 = fixtures.insertException(
                q2, acmeTenantId, productId, apiMeterId, periodId, "PERIOD_FINALIZED", MID_AUG.plusSeconds(1)
        );
        seedSumState(acmeTenantId, 0, 0);
        String runId = startRecon(periodId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> a = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return apply(runId, ex1, "shared-key", "reason-a").then().extract().statusCode();
        });
        Future<Integer> b = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return apply(runId, ex2, "shared-key", "reason-b").then().extract().statusCode();
        });
        start.countDown();
        List<Integer> codes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(fixtures.adjustmentCount()).isEqualTo(1);
    }

    @Test
    void globexCannotAdjustAcme() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String runId = startRecon(periodId);
        givenBearer(TestJwtSupport.billingOperator(globexTenantId))
                .body(body("x-tenant", "nope"))
                .when()
                .post("/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments", runId, seeded.exceptionId())
                .then()
                .statusCode(403);
        assertThat(fixtures.adjustmentCount()).isZero();
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(97L);
    }

    @Test
    void auditorCannotApply() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String runId = startRecon(periodId);
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .body(body("auditor-try", "no"))
                .when()
                .post("/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments", runId, seeded.exceptionId())
                .then()
                .statusCode(403);
        assertThat(fixtures.adjustmentCount()).isZero();
    }

    @Test
    void developerCannotApply() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String runId = startRecon(periodId);
        givenBearer(TestJwtSupport.developer(acmeTenantId))
                .body(body("dev-try", "no"))
                .when()
                .post("/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments", runId, seeded.exceptionId())
                .then()
                .statusCode(403);
    }

    @Test
    void runningReconciliationReference_rejected() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        UUID runningId = UUID.randomUUID();
        fixtures.insertRunningRun(runningId, acmeTenantId, productId, periodId);
        apply(runningId.toString(), seeded.exceptionId(), "run-running", "no")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("RECONCILIATION_RUN_NOT_COMPLETED"));
        assertThat(fixtures.adjustmentCount()).isZero();
        jdbcTemplate.update("DELETE FROM reconciliation_run WHERE id = ?", runningId);
    }

    @Test
    void failedReconciliationReference_rejected() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        UUID failedId = UUID.randomUUID();
        fixtures.insertFailedRun(failedId, acmeTenantId, productId, periodId);
        apply(failedId.toString(), seeded.exceptionId(), "run-failed", "no")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("RECONCILIATION_RUN_NOT_COMPLETED"));
        assertThat(fixtures.adjustmentCount()).isZero();
    }

    @Test
    void runningReconOnPeriod_blocksAdjustmentAgainstCompletedRun() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String completed = startRecon(periodId);
        UUID runningId = UUID.randomUUID();
        fixtures.insertRunningRun(runningId, acmeTenantId, productId, periodId);
        apply(completed, seeded.exceptionId(), "blocked-by-running", "no")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION"));
        jdbcTemplate.update("DELETE FROM reconciliation_run WHERE id = ?", runningId);
        assertThat(fixtures.adjustmentCount()).isZero();
    }

    @Test
    void reconciliationAndAdjustmentRace_coherentSnapshot() throws Exception {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String completed = startRecon(periodId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> recon = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return startRecon(periodId);
        });
        Future<Integer> adj = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return apply(completed, seeded.exceptionId(), "race-adj", "race").then().extract().statusCode();
        });
        start.countDown();
        String runId = recon.get(60, TimeUnit.SECONDS);
        int adjCode = adj.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(adjCode).isIn(200, 409);
        Integer commercial = givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", runId)
                .then()
                .statusCode(200)
                .extract()
                .path("[0].commercialExpectedValue");
        Integer actual = givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", runId)
                .then()
                .extract()
                .path("[0].actualValue");
        assertThat(commercial).isEqualTo(actual);
        assertThat(commercial).isIn(97, 100);
    }

    @Test
    void quotaStateUnchanged_afterAdjustment_mayDiverge() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        SeededException seeded = seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        fixtures.insertQuotaState(acmeTenantId, apiMeterId, WINDOW_START, WINDOW_END, 1000, 97);
        String runId = startRecon(periodId);
        apply(runId, seeded.exceptionId(), "quota-untouched", "reporting only")
                .then()
                .statusCode(200);
        assertThat(fixtures.quotaConsumed(acmeTenantId, apiMeterId)).isEqualTo(97L);
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(100L);
        String verifyRun = startRecon(periodId);
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", verifyRun)
                .then()
                .statusCode(200)
                .body("[0].classification", equalTo("QUOTA_REPORTING_DIVERGENCE"))
                .body("[0].commercialExpectedValue", equalTo(100))
                .body("[0].actualValue", equalTo(100))
                .body("[0].quotaConsumedValue", equalTo(97));
        assertThat(fixtures.quotaConsumed(acmeTenantId, apiMeterId)).isEqualTo(97L);
    }

    @Test
    void unresolvedQuarantine_stillExcluded() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        seedQuarantinedSum(periodId, "PERIOD_FINALIZED");
        String runId = startRecon(periodId);
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{id}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].commercialExpectedValue", equalTo(97))
                .body("[0].unresolvedExceptionCount", equalTo(1))
                .body("[0].adjustedEventCount", equalTo(0));
    }

    private void seedMax(UUID periodId, long existing, long quarantinedQty) {
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, existing, MID_AUG, applied);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, quarantinedQty, MID_AUG.plusSeconds(1), quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acmeTenantId, productId, workspaceMeterId, periodId, "PERIOD_FINALIZED", MID_AUG.plusSeconds(1)
        );
        fixtures.insertWindowAggregate(
                acmeTenantId, productId, workspaceMeterId, MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE,
                "MAX", WINDOW_START, WINDOW_END, existing, 1
        );
        fixtures.insertLifetimeAggregate(
                acmeTenantId, productId, workspaceMeterId, MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE,
                "MAX", existing, 1
        );
        String runId = startRecon(periodId);
        apply(runId, exceptionId, "apply-max-increase", "MAX increases")
                .then()
                .statusCode(200);
        assertThat(fixtures.windowAggregateValue(acmeTenantId, workspaceMeterId)).isEqualTo(quarantinedQty);
        assertThat(fixtures.windowEventCount(acmeTenantId, workspaceMeterId)).isEqualTo(2L);
    }

    private void rejectPeriodStatus(String status) {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, status);
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG, quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acmeTenantId, productId, apiMeterId, periodId, "PERIOD_RECONCILING", MID_AUG
        );
        UUID runId = UUID.randomUUID();
        fixtures.insertCompletedRun(runId, acmeTenantId, productId, periodId);
        apply(runId.toString(), exceptionId, "period-" + status, "no")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD"));
        assertThat(fixtures.adjustmentCount()).isZero();
        assertThat(fixtures.periods().periodStatus(periodId)).isEqualTo(status);
    }

    private SeededException seedQuarantinedSum(UUID periodId, String reason) {
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 97, MID_AUG, applied);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG.plusSeconds(1), quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acmeTenantId, productId, apiMeterId, periodId, reason, MID_AUG.plusSeconds(1)
        );
        seedSumState(acmeTenantId, 97, 1);
        return new SeededException(exceptionId, quarantined);
    }

    private void seedSumState(UUID tenantId, long value, long eventCount) {
        if (eventCount == 0) {
            return;
        }
        fixtures.insertWindowAggregate(
                tenantId, productId, apiMeterId, MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM", WINDOW_START, WINDOW_END, value, eventCount
        );
        fixtures.insertLifetimeAggregate(
                tenantId, productId, apiMeterId, MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM", value, eventCount
        );
    }

    private String startRecon(UUID periodId) {
        return givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .extract()
                .path("reconciliationRunId");
    }

    private io.restassured.response.Response apply(
            String runId,
            UUID exceptionId,
            String key,
            String reason
    ) {
        return givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .body(body(key, reason))
                .when()
                .post("/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments", runId, exceptionId);
    }

    private static Map<String, Object> body(String key, String reason) {
        Map<String, Object> map = new HashMap<>();
        map.put("idempotencyKey", key);
        map.put("reason", reason);
        return map;
    }

    private record SeededException(UUID exceptionId, UUID eventId) {
    }
}
