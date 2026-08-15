package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.micrometer.core.instrument.MeterRegistry;
import io.usagecore.usagepipeline.application.observability.UsagePipelineMetrics;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.ReconciliationFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@AutoConfigureObservability
class UsagePipelineReconciliationObservabilityIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MID_AUG = Instant.parse("2026-08-15T12:00:00Z");

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ReconciliationFixtureSeeder fixtures;
    private UUID acmeTenantId;
    private UUID productId;
    private UUID apiMeterId;

    @DynamicPropertySource
    static void observabilityKafkaGroup(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-recon-obs-test");
    }

    @BeforeEach
    void setUp() {
        fixtures = new ReconciliationFixtureSeeder(jdbcTemplate);
        fixtures.clearUsageEvidence();
        acmeTenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        fixtures.quota().ensureTenant(acmeTenantId, "acme");
        productId = fixtures.meters().ensureDataPilotProductAndMeters();
        apiMeterId = fixtures.meters().meterDefinitionId(MeterDefinitionFixtureSeeder.METER_API_REQUESTS);
    }

    @Test
    void reconciliationMatchMismatchAndAdjustmentMetrics() {
        UUID matchPeriod = fixtures.periods().insertPeriod(
                acmeTenantId, productId, PERIOD_START, PERIOD_END, "RECONCILING"
        );
        fixtures.insertLedgerEvent(
                acmeTenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                40,
                MID_AUG,
                UUID.randomUUID()
        );
        fixtures.insertWindowAggregate(
                acmeTenantId,
                productId,
                apiMeterId,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM",
                WINDOW_START,
                WINDOW_END,
                40,
                1
        );
        double matchBefore = counter(UsagePipelineMetrics.RECONCILIATION_RUNS, "result", "match");
        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", matchPeriod)
                .then()
                .statusCode(200)
                .body("result", equalTo("MATCH"));
        assertThat(counter(UsagePipelineMetrics.RECONCILIATION_RUNS, "result", "match"))
                .isEqualTo(matchBefore + 1.0d);

        UUID mismatchPeriod = fixtures.periods().insertPeriod(
                acmeTenantId,
                productId,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"),
                "RECONCILING"
        );
        fixtures.insertLedgerEvent(
                acmeTenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                10,
                Instant.parse("2026-09-15T12:00:00Z"),
                UUID.randomUUID()
        );
        fixtures.insertWindowAggregate(
                acmeTenantId,
                productId,
                apiMeterId,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM",
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"),
                7,
                1
        );
        double mismatchBefore = counter(UsagePipelineMetrics.RECONCILIATION_RUNS, "result", "mismatch");
        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", mismatchPeriod)
                .then()
                .statusCode(200)
                .body("result", equalTo("MISMATCH"));
        assertThat(counter(UsagePipelineMetrics.RECONCILIATION_RUNS, "result", "mismatch"))
                .isEqualTo(mismatchBefore + 1.0d);
        assertThat(counter(
                UsagePipelineMetrics.RECONCILIATION_MISMATCHES,
                "type",
                "AGGREGATE_VALUE_MISMATCH"
        )).isGreaterThanOrEqualTo(1.0d);

        UUID adjPeriod = fixtures.periods().insertPeriod(
                acmeTenantId,
                productId,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"),
                "FINALIZED"
        );
        UUID quarantined = UUID.randomUUID();
        UUID applied = UUID.randomUUID();
        fixtures.insertLedgerEvent(
                acmeTenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                97,
                Instant.parse("2026-10-15T12:00:00Z"),
                applied
        );
        fixtures.insertLedgerEvent(
                acmeTenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                3,
                Instant.parse("2026-10-15T12:00:01Z"),
                quarantined
        );
        UUID exceptionId = fixtures.insertException(
                quarantined,
                acmeTenantId,
                productId,
                apiMeterId,
                adjPeriod,
                "PERIOD_FINALIZED",
                Instant.parse("2026-10-15T12:00:01Z")
        );
        fixtures.insertWindowAggregate(
                acmeTenantId,
                productId,
                apiMeterId,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM",
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"),
                97,
                1
        );
        fixtures.insertLifetimeAggregate(
                acmeTenantId,
                productId,
                apiMeterId,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM",
                97,
                1
        );
        String runId = givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .when()
                .post("/reconciliation/periods/{id}/runs", adjPeriod)
                .then()
                .statusCode(200)
                .extract()
                .path("reconciliationRunId");
        double appliedBefore = counter(UsagePipelineMetrics.USAGE_ADJUSTMENTS, "result", "applied");
        Map<String, Object> body = new HashMap<>();
        body.put("idempotencyKey", "obs-adjust-1");
        body.put("reason", "Approved for observability evidence");
        givenBearer(TestJwtSupport.billingOperator(acmeTenantId))
                .body(body)
                .when()
                .post("/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments", runId, exceptionId)
                .then()
                .statusCode(200);
        assertThat(counter(UsagePipelineMetrics.USAGE_ADJUSTMENTS, "result", "applied"))
                .isEqualTo(appliedBefore + 1.0d);
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
