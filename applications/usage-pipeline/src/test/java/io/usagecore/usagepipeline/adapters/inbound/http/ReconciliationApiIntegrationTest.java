package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRepository;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunStatus;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.ReconciliationFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 8A reconciliation flagship matrix (PostgreSQL Testcontainers).
 * Reconciliation is read/rebuild/compare/report — never repairs derived state.
 */
class ReconciliationApiIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MID_AUG = Instant.parse("2026-08-15T12:00:00Z");
    private static final Instant LATE_AUG = Instant.parse("2026-08-31T23:59:00Z");
    private static final Instant SEP_1 = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReconciliationRepository reconciliationRepository;

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
    void reconcilingPeriod_sumMatch_flagship() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 40, 3);

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("result", equalTo("MATCH"))
                .body("matchedMeters", equalTo(1))
                .body("mismatchedMeters", equalTo(0))
                .body("canonicalEventCount", equalTo(3))
                .body("quarantinedEventCount", equalTo(0));

        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(40L);
    }

    @Test
    void finalizedPeriod_allowedReadOnly_andDoesNotMutate() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 40, 3);

        givenBearer(TestJwtSupport.platformAdmin())
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("result", equalTo("MATCH"));

        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(40L);
        assertThat(fixtures.periods().periodStatus(periodId)).isEqualTo("FINALIZED");
    }

    @Test
    void openPeriod_rejected() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "OPEN");
        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("RECONCILIATION_CONFLICT"));
    }

    @Test
    void closingPeriod_rejected() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "CLOSING");
        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("RECONCILIATION_CONFLICT"));
    }

    @Test
    void aggregateValueMismatch_flagship_doesNotRepair() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 37, 3);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("result", equalTo("MISMATCH"))
                .body("mismatchedMeters", equalTo(1))
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].commercialExpectedValue", equalTo(40))
                .body("[0].actualValue", equalTo(37))
                .body("[0].difference", equalTo(-3))
                .body("[0].classification", equalTo("AGGREGATE_VALUE_MISMATCH"))
                .body("[0].status", equalTo("MISMATCH"));

        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(37L);
    }

    @Test
    void missingAggregate_flagship() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 20, 30);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MISMATCH"))
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].commercialExpectedValue", equalTo(60))
                .body("[0].actualValue", nullValue())
                .body("[0].classification", equalTo("MISSING_AGGREGATE"));

        assertThat(fixtures.windowAggregateCount(acmeTenantId, apiMeterId)).isZero();
    }

    @Test
    void unexpectedAggregate_flagship() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumAggregate(acmeTenantId, 42, 1);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MISMATCH"))
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].classification", equalTo("UNEXPECTED_AGGREGATE"))
                .body("[0].actualValue", equalTo(42))
                .body("[0].commercialExpectedValue", equalTo(0));

        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(42L);
    }

    @Test
    void countAndMaxAndMultiMeter_match() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 40, 3);

        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, 99, MID_AUG, e1);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, 1, MID_AUG.plusSeconds(1), e2);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, 7, MID_AUG.plusSeconds(2), e3);
        fixtures.insertWindowAggregate(
                acmeTenantId, productId, exportMeterId, MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT,
                "COUNT", WINDOW_START, WINDOW_END, 3, 3
        );

        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, 10, MID_AUG, UUID.randomUUID());
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, 25, MID_AUG.plusSeconds(1), UUID.randomUUID());
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, 5, MID_AUG.plusSeconds(2), UUID.randomUUID());
        fixtures.insertWindowAggregate(
                acmeTenantId, productId, workspaceMeterId, MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE,
                "MAX", WINDOW_START, WINDOW_END, 25, 3
        );

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MATCH"))
                .body("matchedMeters", equalTo(3))
                .body("mismatchedMeters", equalTo(0));
    }

    @Test
    void quarantinedEvent_visibleSeparately_notCountedAsCommercial() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        UUID applied1 = UUID.randomUUID();
        UUID applied2 = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 50, MID_AUG, applied1);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 47, MID_AUG.plusSeconds(1), applied2);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG.plusSeconds(2), quarantined);
        fixtures.insertException(
                quarantined, acmeTenantId, productId, apiMeterId, periodId, "PERIOD_RECONCILING", MID_AUG.plusSeconds(2)
        );
        seedSumAggregate(acmeTenantId, 97, 2);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("canonicalEventCount", equalTo(3))
                .body("quarantinedEventCount", equalTo(1))
                .body("result", equalTo("MATCH"))
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].observedExpectedValue", equalTo(100))
                .body("[0].commercialExpectedValue", equalTo(97))
                .body("[0].actualValue", equalTo(97))
                .body("[0].quarantinedEventCount", equalTo(1))
                .body("[0].observedEventCount", equalTo(3))
                .body("[0].expectedEventCount", equalTo(2));
    }

    @Test
    void quotaReportingDivergence_flagship() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        UUID applied1 = UUID.randomUUID();
        UUID applied2 = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 50, MID_AUG, applied1);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 47, MID_AUG.plusSeconds(1), applied2);
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG.plusSeconds(2), quarantined);
        fixtures.insertException(
                quarantined, acmeTenantId, productId, apiMeterId, periodId, "PERIOD_RECONCILING", MID_AUG.plusSeconds(2)
        );
        seedSumAggregate(acmeTenantId, 97, 2);
        fixtures.insertQuotaState(acmeTenantId, apiMeterId, WINDOW_START, WINDOW_END, 1000, 100);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MISMATCH"))
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].classification", equalTo("QUOTA_REPORTING_DIVERGENCE"))
                .body("[0].commercialExpectedValue", equalTo(97))
                .body("[0].actualValue", equalTo(97))
                .body("[0].quotaConsumedValue", equalTo(100))
                .body("[0].quarantinedEventCount", equalTo(1));

        assertThat(fixtures.quotaConsumed(acmeTenantId, apiMeterId)).isEqualTo(100L);
        assertThat(fixtures.windowAggregateValue(acmeTenantId, apiMeterId)).isEqualTo(97L);
    }

    @Test
    void eventTimePeriodBoundary_halfOpen() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 10, LATE_AUG, UUID.randomUUID());
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 99, SEP_1, UUID.randomUUID());
        seedSumAggregate(acmeTenantId, 10, 1);

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MATCH"))
                .body("canonicalEventCount", equalTo(1));
    }

    @Test
    void lateAppliedEvent_includedByOccurredAt() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 40, LATE_AUG, UUID.randomUUID());
        seedSumAggregate(acmeTenantId, 40, 1);

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MATCH"))
                .body("canonicalEventCount", equalTo(1));
    }

    @Test
    void duplicateEventId_cannotContributeTwice_uniqueLedger() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        UUID eventId = UUID.randomUUID();
        fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 40, MID_AUG, eventId);
        seedSumAggregate(acmeTenantId, 40, 1);

        // Second insert with same event_id must fail at DB — proves uniqueness, not dual contribution.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fixtures.insertLedgerEvent(acmeTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                        MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 40, MID_AUG, eventId)
        ).isInstanceOf(Exception.class);

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MATCH"))
                .body("canonicalEventCount", equalTo(1));
    }

    @Test
    void repeatedRuns_deterministicBusinessResult() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 40, 3);

        var run1 = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        var run2 = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(run1.getString("reconciliationRunId")).isNotEqualTo(run2.getString("reconciliationRunId"));
        assertThat(run1.getString("result")).isEqualTo(run2.getString("result"));
        assertThat(run1.getInt("matchedMeters")).isEqualTo(run2.getInt("matchedMeters"));
        assertThat(run1.getInt("mismatchedMeters")).isEqualTo(run2.getInt("mismatchedMeters"));
        assertThat(run1.getInt("canonicalEventCount")).isEqualTo(run2.getInt("canonicalEventCount"));

        var items1 = givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", run1.getString("reconciliationRunId"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        var items2 = givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", run2.getString("reconciliationRunId"))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertThat(items1.getInt("[0].commercialExpectedValue"))
                .isEqualTo(items2.getInt("[0].commercialExpectedValue"));
        assertThat(items1.getInt("[0].actualValue")).isEqualTo(items2.getInt("[0].actualValue"));
        assertThat(items1.getString("[0].classification")).isEqualTo(items2.getString("[0].classification"));
        assertThat(fixtures.runCountForPeriod(periodId)).isEqualTo(2L);
    }

    @Test
    void tenantIsolation_acmeDoesNotSeeGlobex() {
        UUID acmePeriod = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        UUID globexPeriod = fixtures.periods().insertPeriod(globexTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");

        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 40, 3);

        fixtures.insertLedgerEvent(globexTenantId, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1000, MID_AUG, UUID.randomUUID());
        fixtures.insertWindowAggregate(
                globexTenantId, productId, apiMeterId, MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM", WINDOW_START, WINDOW_END, 1000, 1
        );

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", acmePeriod)
                .then()
                .statusCode(200)
                .body("result", equalTo("MATCH"))
                .body("canonicalEventCount", equalTo(3));

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", globexPeriod)
                .then()
                .statusCode(403);
    }

    @Test
    void crossTenantApiDenial_globexCannotReadAcmeRun() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10);
        seedSumAggregate(acmeTenantId, 10, 1);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.billingOperator(globexTenantId))
                .when()
                .get("/reconciliation/runs/{runId}", runId)
                .then()
                .statusCode(403);

        givenBearer(TestJwtSupport.auditor(globexTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(403);
    }

    @Test
    void concurrentStarts_oneActiveWinner() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 25, 5);
        seedSumAggregate(acmeTenantId, 40, 3);

        UUID blocker = UUID.randomUUID();
        fixtures.insertRunningRun(blocker, acmeTenantId, productId, periodId);

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("RECONCILIATION_CONFLICT"));

        assertThat(fixtures.runningCountForPeriod(periodId)).isEqualTo(1L);

        // Second RUNNING insert for same period is rejected by partial unique index.
        UUID second = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fixtures.insertRunningRun(second, acmeTenantId, productId, periodId)
        ).isInstanceOf(Exception.class);

        assertThat(fixtures.runningCountForPeriod(periodId)).isEqualTo(1L);

        // After blocker completes path is cleared, a new run may start.
        jdbcTemplate.update(
                """
                UPDATE reconciliation_run
                SET status = 'COMPLETED', result = 'MATCH', completed_at = started_at,
                    canonical_event_count = 0, quarantined_event_count = 0,
                    matched_meter_count = 0, mismatched_meter_count = 0
                WHERE id = ?
                """,
                blocker
        );

        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));

        assertThat(fixtures.runningCountForPeriod(periodId)).isZero();
        assertThat(fixtures.runCountForPeriod(periodId)).isEqualTo(2L);
    }

    @Test
    void reconciliationFailure_marksFailedEvidence() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        UUID runId = UUID.randomUUID();
        fixtures.insertRunningRun(runId, acmeTenantId, productId, periodId);

        reconciliationRepository.markFailed(runId, Instant.parse("2026-09-02T12:05:00Z"), "simulated rebuild failure");

        assertThat(fixtures.runStatus(runId)).isEqualTo(ReconciliationRunStatus.FAILED.name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM reconciliation_run WHERE id = ?",
                String.class,
                runId
        )).isEqualTo("simulated rebuild failure");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result FROM reconciliation_run WHERE id = ?",
                String.class,
                runId
        )).isNull();
        Long itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_item WHERE reconciliation_run_id = ?",
                Long.class,
                runId
        );
        assertThat(itemCount).isZero();
    }

    @Test
    void eventCountMismatch_detected() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        seedSumEvents(acmeTenantId, 10, 30);
        // Same value 40, wrong event_count
        seedSumAggregate(acmeTenantId, 40, 9);

        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .body("result", equalTo("MISMATCH"))
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .get("/reconciliation/runs/{runId}/items", runId)
                .then()
                .statusCode(200)
                .body("[0].classification", equalTo("EVENT_COUNT_MISMATCH"));
    }

    @Test
    void developerCannotInitiate_auditorCannotInitiate() {
        UUID periodId = fixtures.periods().insertPeriod(acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING");
        givenBearer(TestJwtSupport.developer(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(403);
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(403);
    }

    private void seedSumEvents(UUID tenantId, long... quantities) {
        for (int i = 0; i < quantities.length; i++) {
            fixtures.insertLedgerEvent(
                    tenantId,
                    MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                    MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                    quantities[i],
                    MID_AUG.plusSeconds(i),
                    UUID.randomUUID()
            );
        }
    }

    private void seedSumAggregate(UUID tenantId, long value, long eventCount) {
        fixtures.insertWindowAggregate(
                tenantId,
                productId,
                apiMeterId,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM",
                WINDOW_START,
                WINDOW_END,
                value,
                eventCount
        );
    }
}
