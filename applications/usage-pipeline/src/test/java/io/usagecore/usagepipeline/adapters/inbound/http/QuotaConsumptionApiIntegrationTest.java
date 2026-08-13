package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Phase 6C: synchronous contract-aware quota consumption via {@code POST /api/v1/usage/consume}.
 */
class QuotaConsumptionApiIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant OCCURRED = Instant.parse("2026-08-13T10:00:00Z");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant JAN_START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private QuotaCommercialFixtureSeeder seeder;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM quota_consumption");
        jdbc.update("DELETE FROM quota_state");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM usage_ingestion");
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("DELETE FROM contract_version");
        jdbc.update("DELETE FROM contract");

        seeder = new QuotaCommercialFixtureSeeder(jdbc);
        seeder.ensureTenant(ACME, "acme");
        seeder.ensureTenant(GLOBEX, "globex");
        seeder.ensureCatalogue();
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-dp",
                1,
                JAN_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                100L
        );
    }

    @Test
    void limitedWithinQuota_acceptedWithinQuota() {
        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 10, "within-1"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"))
                .body("configuredLimit", equalTo(100))
                .body("consumed", equalTo(10))
                .body("remaining", equalTo(90))
                .body("consumptionId", notNullValue())
                .body("eventId", notNullValue())
                .body("idempotentReplay", equalTo(false))
                .body("contractVersionNumber", equalTo(1));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(10L);
        assertThat(countIngestions()).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
    }

    @Test
    void limitedExactlyReachesQuota_accepted() {
        seeder.seedQuotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START, 100L, 90L
        );

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 10, "exact-100"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"))
                .body("configuredLimit", equalTo(100))
                .body("consumed", equalTo(100))
                .body("remaining", equalTo(0));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(100L);
    }

    @Test
    void limitedExceedsQuota_rejectedQuotaExhausted() {
        seeder.seedQuotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START, 100L, 100L
        );

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1, "exhausted-1"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("QUOTA_EXHAUSTED"))
                .body("configuredLimit", equalTo(100))
                .body("consumed", equalTo(100))
                .body("remaining", equalTo(0))
                .body("eventId", nullValue());

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(100L);
        assertThat(countIngestions()).isZero();
        assertThat(outboxEventRepository.countAll()).isZero();
    }

    @Test
    void largeAllOrNothingRequest_rejectedLeavesConsumedUnchanged() {
        seeder.seedQuotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START, 100L, 90L
        );

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 20, "all-or-nothing"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("QUOTA_EXHAUSTED"))
                .body("configuredLimit", equalTo(100))
                .body("consumed", equalTo(90))
                .body("remaining", equalTo(10));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(90L);
        assertThat(countIngestions()).isZero();
        assertThat(outboxEventRepository.countAll()).isZero();
    }

    @Test
    void disabledEntitlement_rejectedWithoutUsageOrQuotaMutation() {
        resetCommercial();
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-dp",
                1,
                JAN_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "DISABLED",
                null
        );

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 5, "disabled-1"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("ENTITLEMENT_DISABLED"))
                .body("configuredLimit", nullValue())
                .body("consumed", nullValue())
                .body("remaining", nullValue())
                .body("eventId", nullValue());

        assertThat(countQuotaState(ACME)).isZero();
        assertThat(countIngestions()).isZero();
        assertThat(outboxEventRepository.countAll()).isZero();
        assertThat(countConsumptions()).isEqualTo(1);
    }

    @Test
    void enabledEntitlement_acceptedWithoutQuotaCounters() {
        resetCommercial();
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-dp",
                1,
                JAN_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "ENABLED",
                null
        );

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 42, "enabled-1"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("ENTITLEMENT_ENABLED"))
                .body("configuredLimit", nullValue())
                .body("consumed", nullValue())
                .body("remaining", nullValue())
                .body("eventId", notNullValue())
                .body("consumptionId", notNullValue());

        assertThat(countQuotaState(ACME)).isZero();
        assertThat(countIngestions()).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
    }

    @Test
    void countMeter_threeEventsConsumeOneEach_fourthRejected() {
        seedLimitedEntitlementOnExistingVersion(
                MeterDefinitionFixtureSeeder.FEATURE_SCHEDULED_EXPORT,
                3L
        );

        for (int i = 1; i <= 3; i++) {
            givenBearer(developerToken(ACME))
                    .body(body(MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, 100L * i, "count-" + i))
                    .when()
                    .post("/usage/consume")
                    .then()
                    .statusCode(200)
                    .body("decision", equalTo("ACCEPTED"))
                    .body("reason", equalTo("WITHIN_QUOTA"))
                    .body("consumed", equalTo(i))
                    .body("remaining", equalTo(3 - i));
        }

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, 999, "count-4"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("QUOTA_EXHAUSTED"))
                .body("consumed", equalTo(3))
                .body("remaining", equalTo(0));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT, AUG_START, SEP_START
        )).isEqualTo(3L);
        assertThat(countIngestions()).isEqualTo(3);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(3);
    }

    @Test
    void maxMeterLimited_rejectedUnsupportedQuotaMeterType() {
        seedLimitedEntitlementOnExistingVersion(MeterDefinitionFixtureSeeder.FEATURE_WORKSPACE, 50L);

        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_WORKSPACE_SIZE, 10, "max-unsupported"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("UNSUPPORTED_QUOTA_METER_TYPE"))
                .body("configuredLimit", equalTo(50))
                .body("consumed", nullValue())
                .body("remaining", nullValue())
                .body("eventId", nullValue());

        assertThat(countQuotaState(ACME)).isZero();
        assertThat(countIngestions()).isZero();
        assertThat(outboxEventRepository.countAll()).isZero();
    }

    @Test
    void tenantIsolation_acmeAndGlobexConcurrentDoNotInterfere() throws Exception {
        seeder.seedActivatedEntitlement(
                GLOBEX,
                "globex-dp",
                1,
                JAN_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                100L
        );

        int perTenant = 40;
        int threads = perTenant * 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < perTenant; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    return consumeDecision(
                            ACME,
                            body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1, "iso-acme-" + index)
                    );
                }));
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    return consumeDecision(
                            GLOBEX,
                            body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1, "iso-globex-" + index)
                    );
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long accepted = 0;
            for (Future<String> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo("ACCEPTED");
                accepted++;
            }
            assertThat(accepted).isEqualTo(perTenant * 2L);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(perTenant);
        assertThat(seeder.quotaConsumed(
                GLOBEX, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(perTenant);
    }

    @Test
    void contractVersionTemporalBoundary_augUsesV1_sepUsesV2() {
        resetCommercial();
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-dp",
                1,
                AUG_START,
                SEP_START,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                100L
        );
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-dp",
                2,
                SEP_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                500L
        );

        Instant aug31 = Instant.parse("2026-08-31T12:00:00Z");
        Instant sep1 = Instant.parse("2026-09-01T00:00:00Z");

        givenBearer(developerToken(ACME))
                .body(bodyAt(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1, "temporal-aug", aug31))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"))
                .body("configuredLimit", equalTo(100))
                .body("consumed", equalTo(1))
                .body("remaining", equalTo(99))
                .body("contractVersionNumber", equalTo(1));

        givenBearer(developerToken(ACME))
                .body(bodyAt(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1, "temporal-sep", sep1))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"))
                .body("configuredLimit", equalTo(500))
                .body("consumed", equalTo(1))
                .body("remaining", equalTo(499))
                .body("contractVersionNumber", equalTo(2));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(1L);
        assertThat(seeder.quotaConsumed(
                ACME,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                SEP_START,
                Instant.parse("2026-10-01T00:00:00Z")
        )).isEqualTo(1L);
    }

    @Test
    void duplicateIdenticalSubmissionsTimes100_oneConsumptionAndOneQuotaEffect() {
        String expectedConsumptionId = null;
        String expectedEventId = null;

        for (int i = 0; i < 100; i++) {
            var response = givenBearer(developerToken(ACME))
                    .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 7, "dup-storm"))
                    .when()
                    .post("/usage/consume")
                    .then()
                    .statusCode(200)
                    .body("decision", equalTo("ACCEPTED"))
                    .body("reason", equalTo("WITHIN_QUOTA"))
                    .body("idempotentReplay", equalTo(i > 0))
                    .extract()
                    .response();

            String consumptionId = response.path("consumptionId");
            String eventId = response.path("eventId");
            if (expectedConsumptionId == null) {
                expectedConsumptionId = consumptionId;
                expectedEventId = eventId;
            } else {
                assertThat(consumptionId).isEqualTo(expectedConsumptionId);
                assertThat(eventId).isEqualTo(expectedEventId);
            }
        }

        assertThat(countConsumptions()).isEqualTo(1);
        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(7L);
        assertThat(countIngestions()).isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    @Test
    void conflictingIdempotencyKey_returns409() {
        givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 5, "conflict-key"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"));

        Map<String, Object> conflicting = body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 9, "conflict-key");
        givenBearer(developerToken(ACME))
                .body(conflicting)
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("IDEMPOTENCY_CONFLICT"));

        assertThat(countConsumptions()).isEqualTo(1);
        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(5L);
        assertThat(countIngestions()).isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    @Test
    void kafkaUnavailable_acceptLeavesOutboxPending() {
        String eventId = givenBearer(developerToken(ACME))
                .body(body(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 3, "pending-outbox"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"))
                .body("eventId", notNullValue())
                .extract()
                .path("eventId");

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
        assertThat(outboxEventRepository.findByEventId(UUID.fromString(eventId)))
                .isPresent()
                .get()
                .extracting(row -> row.status())
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isZero();
    }

    @Test
    void unboundLegacyMeter_rejectedWithoutQuotaOrUsageEffect() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID productId = jdbc.queryForObject(
                "SELECT id FROM product WHERE product_key = ?",
                UUID.class,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY
        );
        // Simulate a V9→V10 leftover unbound row under the NOT VALID binding constraint.
        jdbc.update("ALTER TABLE meter_definition DROP CONSTRAINT ck_meter_definition_feature_id_required");
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, NULL, 'legacy_unbound_meter', 'Legacy Unbound', 'SUM', 'MONTHLY', 'ACTIVE', ?, ?)
                """,
                UUID.nameUUIDFromBytes("legacy_unbound_meter".getBytes()),
                productId,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now)
        );
        jdbc.update(
                """
                ALTER TABLE meter_definition
                    ADD CONSTRAINT ck_meter_definition_feature_id_required
                        CHECK (feature_id IS NOT NULL) NOT VALID
                """
        );

        givenBearer(developerToken(ACME))
                .body(body("legacy_unbound_meter", 1, "unbound-consume-1"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("METER_NOT_BOUND_TO_FEATURE"))
                .body("eventId", nullValue())
                .body("consumed", nullValue())
                .body("remaining", nullValue());

        assertThat(countQuotaState(ACME)).isZero();
        assertThat(countIngestions()).isZero();
        assertThat(outboxEventRepository.countAll()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_consumption WHERE decision = 'ACCEPTED'",
                Long.class
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM quota_consumption
                WHERE idempotency_key = 'unbound-consume-1' AND reason = 'METER_NOT_BOUND_TO_FEATURE'
                """,
                Long.class
        )).isEqualTo(1);
    }

    private void resetCommercial() {
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("DELETE FROM contract_version");
        jdbc.update("DELETE FROM contract");
    }

    /**
     * Adds another LIMITED entitlement on Acme contract version 1 (avoids activated-version
     * exclusion constraint when effective windows would overlap).
     */
    private void seedLimitedEntitlementOnExistingVersion(String featureKey, long limitQuantity) {
        UUID versionId = jdbc.queryForObject(
                """
                SELECT cv.id
                FROM contract_version cv
                JOIN contract c ON c.id = cv.contract_id
                WHERE c.tenant_id = ? AND cv.version_number = 1
                """,
                UUID.class,
                ACME
        );
        UUID featureId = seeder.meters().featureId(featureKey);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO entitlement (
                    id, contract_version_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                ) VALUES (?, ?, ?, 'LIMITED', ?, ?, ?)
                """,
                UUID.randomUUID(),
                versionId,
                featureId,
                limitQuantity,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now)
        );
    }

    private String consumeDecision(UUID tenantId, Map<String, Object> body) {
        return givenBearer(developerToken(tenantId))
                .body(body)
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .extract()
                .path("decision");
    }

    private static Map<String, Object> body(String meterKey, long quantity, String idempotencyKey) {
        return bodyAt(meterKey, quantity, idempotencyKey, OCCURRED);
    }

    private static Map<String, Object> bodyAt(
            String meterKey,
            long quantity,
            String idempotencyKey,
            Instant occurredAt
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", MeterDefinitionFixtureSeeder.PRODUCT_KEY);
        body.put("meterKey", meterKey);
        body.put("quantity", quantity);
        body.put("occurredAt", occurredAt.toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    private long countIngestions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM usage_ingestion", Long.class);
        return count == null ? 0L : count;
    }

    private long countConsumptions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM quota_consumption", Long.class);
        return count == null ? 0L : count;
    }

    private long countQuotaState(UUID tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_state WHERE tenant_id = ?",
                Long.class,
                tenantId
        );
        return count == null ? 0L : count;
    }
}
