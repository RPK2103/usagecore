package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.support.CommercialPeriodFixtureSeeder;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 7: strict {@code /usage/consume} rejects CLOSING / RECONCILING / FINALIZED periods.
 */
class CommercialPeriodQuotaIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME = UUID.fromString("cccccccc-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-13T10:00:00Z");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant JAN_START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private QuotaCommercialFixtureSeeder seeder;
    private CommercialPeriodFixtureSeeder periods;
    private UUID productId;

    @BeforeEach
    void setUp() {
        periods = new CommercialPeriodFixtureSeeder(jdbc);
        periods.clearCommercialTables();
        jdbc.update("DELETE FROM quota_consumption");
        jdbc.update("DELETE FROM quota_state");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM usage_ingestion");
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("DELETE FROM contract_version");
        jdbc.update("DELETE FROM contract");

        seeder = new QuotaCommercialFixtureSeeder(jdbc);
        seeder.ensureTenant(ACME, "acme-period-quota");
        productId = seeder.ensureCatalogue();
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-period-quota-dp",
                1,
                JAN_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                100L
        );
    }

    @Test
    void noPeriod_consumeStillAccepted() {
        givenBearer(developerToken(ACME))
                .body(body("no-period-consume", 5))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(5L);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
    }

    @Test
    void openPeriod_consumeAccepted() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "OPEN");

        givenBearer(developerToken(ACME))
                .body(body("open-consume", 6))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"));
    }

    @Test
    void closingPeriod_consumeRejected_noMutation() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "CLOSING");

        givenBearer(developerToken(ACME))
                .body(body("closing-consume", 5))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("PERIOD_CLOSING"))
                .body("eventId", nullValue());

        assertThat(countQuotaState()).isZero();
        assertThat(countIngestions()).isZero();
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
    }

    @Test
    void reconcilingPeriod_consumeRejected() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "RECONCILING");

        givenBearer(developerToken(ACME))
                .body(body("reconciling-consume", 5))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("PERIOD_RECONCILING"));

        assertThat(countQuotaState()).isZero();
    }

    @Test
    void finalizedPeriod_consumeRejected_idempotentReplay() {
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "FINALIZED");

        givenBearer(developerToken(ACME))
                .body(body("finalized-consume", 5))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("PERIOD_FINALIZED"))
                .body("idempotentReplay", equalTo(false));

        givenBearer(developerToken(ACME))
                .body(body("finalized-consume", 5))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("PERIOD_FINALIZED"))
                .body("idempotentReplay", equalTo(true));

        assertThat(countQuotaState()).isZero();
        assertThat(countIngestions()).isZero();
    }

    private Map<String, Object> body(String idempotencyKey, long quantity) {
        return Map.of(
                "productKey", MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                "meterKey", MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                "quantity", quantity,
                "occurredAt", OCCURRED.toString(),
                "idempotencyKey", idempotencyKey
        );
    }

    private long countIngestions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM usage_ingestion", Long.class);
        return count == null ? 0L : count;
    }

    private long countQuotaState() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM quota_state WHERE tenant_id = ?", Long.class, ACME);
        return count == null ? 0L : count;
    }
}
