package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6C flagship concurrency evidence against real PostgreSQL quota_state updates.
 */
class QuotaConsumptionConcurrencyIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Instant OCCURRED = Instant.parse("2026-08-13T10:00:00Z");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant JAN_START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

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
    void ninetyConsumedPlusTwentyConcurrent_exactlyTenAcceptedTenRejected() throws Exception {
        seeder.seedQuotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START, 100L, 90L
        );

        int threads = 20;
        List<String> decisions = runConcurrent(threads, index -> body(1, "race-fill-" + index));

        long accepted = decisions.stream().filter("ACCEPTED"::equals).count();
        long rejected = decisions.stream().filter("REJECTED"::equals).count();
        assertThat(accepted).isEqualTo(10);
        assertThat(rejected).isEqualTo(10);
        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(100L);
    }

    @Test
    void oneHundredConcurrentFromZero_exactlyOneHundredAcceptedThenOneMoreRejected() throws Exception {
        int threads = 100;
        List<String> decisions = runConcurrent(threads, index -> body(1, "from-zero-" + index));

        assertThat(decisions).hasSize(100).allMatch("ACCEPTED"::equals);
        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(100L);

        givenBearer(developerToken(ACME))
                .body(body(1, "from-zero-over"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo("QUOTA_EXHAUSTED"))
                .body("consumed", equalTo(100))
                .body("remaining", equalTo(0));

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(100L);
    }

    @Test
    void concurrentSixtyPlusSixtyAgainstLimit100_oneAcceptedFinalConsumedSixty() throws Exception {
        List<String> decisions = runConcurrent(2, index -> body(60, "big-race-" + index));

        long accepted = decisions.stream().filter("ACCEPTED"::equals).count();
        long rejected = decisions.stream().filter("REJECTED"::equals).count();
        assertThat(accepted).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(60L);
    }

    @Test
    void concurrentIdenticalIdempotencyKey_twentyThreads_oneLogicalEffect() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    return givenBearer(developerToken(ACME))
                            .body(body(11, "idem-concurrent"))
                            .when()
                            .post("/usage/consume")
                            .then()
                            .statusCode(200)
                            .body("decision", equalTo("ACCEPTED"))
                            .extract()
                            .path("$");
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<String> consumptionIds = new HashSet<>();
            Set<String> eventIds = new HashSet<>();
            AtomicInteger replayCount = new AtomicInteger();
            for (Future<Map<String, Object>> future : futures) {
                Map<String, Object> response = future.get(60, TimeUnit.SECONDS);
                consumptionIds.add(String.valueOf(response.get("consumptionId")));
                eventIds.add(String.valueOf(response.get("eventId")));
                if (Boolean.TRUE.equals(response.get("idempotentReplay"))) {
                    replayCount.incrementAndGet();
                }
            }

            assertThat(consumptionIds).hasSize(1);
            assertThat(eventIds).hasSize(1);
            assertThat(replayCount.get()).isEqualTo(threads - 1);
            assertThat(countConsumptions()).isEqualTo(1);
            assertThat(countIngestions()).isEqualTo(1);
            assertThat(countOutbox()).isEqualTo(1);
            assertThat(seeder.quotaConsumed(
                    ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
            )).isEqualTo(11L);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<String> runConcurrent(int threads, java.util.function.IntFunction<Map<String, Object>> bodyFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    return consumeDecision(bodyFactory.apply(index));
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> decisions = new ArrayList<>(threads);
            for (Future<String> future : futures) {
                decisions.add(future.get(90, TimeUnit.SECONDS));
            }
            return decisions;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String consumeDecision(Map<String, Object> body) {
        return givenBearer(developerToken(ACME))
                .body(body)
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .extract()
                .path("decision");
    }

    private static Map<String, Object> body(long quantity, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", MeterDefinitionFixtureSeeder.PRODUCT_KEY);
        body.put("meterKey", MeterDefinitionFixtureSeeder.METER_API_REQUESTS);
        body.put("quantity", quantity);
        body.put("occurredAt", OCCURRED.toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    private long countConsumptions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM quota_consumption", Long.class);
        return count == null ? 0L : count;
    }

    private long countIngestions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM usage_ingestion", Long.class);
        return count == null ? 0L : count;
    }

    private long countOutbox() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class);
        return count == null ? 0L : count;
    }
}
