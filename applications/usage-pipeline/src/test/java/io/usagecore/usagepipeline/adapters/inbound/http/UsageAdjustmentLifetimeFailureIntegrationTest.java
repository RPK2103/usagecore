package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRepository;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.ReconciliationFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(UsageAdjustmentLifetimeFailureIntegrationTest.FailingLifetimeConfig.class)
class UsageAdjustmentLifetimeFailureIntegrationTest extends AbstractUsageApiIntegrationTest {

    @DynamicPropertySource
    static void disableKafkaListener(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-adj-lifetime-fail");
    }

    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MID_AUG = Instant.parse("2026-08-15T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ReconciliationFixtureSeeder fixtures;
    private UUID acme;
    private UUID productId;
    private UUID apiMeterId;

    @BeforeEach
    void setUp() {
        fixtures = new ReconciliationFixtureSeeder(jdbcTemplate);
        fixtures.clearUsageEvidence();
        acme = UUID.fromString("11111111-1111-1111-1111-111111111111");
        fixtures.quota().ensureTenant(acme, "acme");
        productId = fixtures.meters().ensureDataPilotProductAndMeters();
        apiMeterId = fixtures.meters().meterDefinitionId(MeterDefinitionFixtureSeeder.METER_API_REQUESTS);
    }

    @Test
    void lifetimeAggregateFailure_rollsBackAdjustment() {
        UUID periodId = fixtures.periods().insertPeriod(acme, productId, PERIOD_START, PERIOD_END, "FINALIZED");
        UUID applied = UUID.randomUUID();
        UUID quarantined = UUID.randomUUID();
        fixtures.insertLedgerEvent(acme, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 97, MID_AUG, applied);
        fixtures.insertLedgerEvent(acme, MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, MID_AUG.plusSeconds(1), quarantined);
        UUID exceptionId = fixtures.insertException(
                quarantined, acme, productId, apiMeterId, periodId, "PERIOD_FINALIZED", MID_AUG.plusSeconds(1)
        );
        fixtures.insertWindowAggregate(
                acme, productId, apiMeterId, MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM", WINDOW_START, WINDOW_END, 97, 1
        );
        fixtures.insertLifetimeAggregate(
                acme, productId, apiMeterId, MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "SUM", 97, 1
        );
        String runId = givenBearer(TestJwtSupport.billingOperator(acme))
                .when()
                .post("/reconciliation/periods/{id}/runs", periodId)
                .then()
                .statusCode(200)
                .extract()
                .path("reconciliationRunId");

        givenBearer(TestJwtSupport.billingOperator(acme))
                .body(Map.of("idempotencyKey", "fail-lifetime", "reason", "should roll back"))
                .when()
                .post("/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments", runId, exceptionId)
                .then()
                .statusCode(500);

        assertThat(fixtures.adjustmentCount()).isZero();
        assertThat(fixtures.windowAggregateValue(acme, apiMeterId)).isEqualTo(97L);
        assertThat(fixtures.lifetimeAggregateValue(acme, apiMeterId)).isEqualTo(97L);
    }

    @TestConfiguration
    static class FailingLifetimeConfig {
        @Bean
        @Primary
        UsageAggregateRepository failingLifetime() {
            return new UsageAggregateRepository() {
                @Override
                public void applyEvent(
                        UUID tenantId,
                        ActiveMeterDefinition meter,
                        long quantity,
                        Instant occurredAt,
                        Instant updatedAt
                ) {
                    throw new IllegalStateException("simulated lifetime aggregate persistence failure");
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
                    return 0;
                }
            };
        }
    }
}
