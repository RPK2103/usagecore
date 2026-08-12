package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsageIngestionRepository;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
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

class DurableUsageIdempotencyIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BETA_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UsageIngestionRepository usageIngestionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
    }

    @Test
    void firstRequest_createsOneIngestionAndOneOutbox() {
        String eventId = postAccepted(ACME_TENANT, body("export-job-first"), "corr-first", false);

        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "export-job-first"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
        assertThat(outboxEventRepository.findByEventId(UUID.fromString(eventId)))
                .isPresent()
                .get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(OutboxStatus.PENDING);
                    assertThat(row.partitionKey()).isEqualTo(
                            UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export")
                    );
                });
    }

    @Test
    void sameTenantSameKeySamePayload_returnsSameEventId_noExtraOutbox() {
        String first = postAccepted(ACME_TENANT, body("export-job-replay"), "corr-a", false);
        String second = postAccepted(ACME_TENANT, body("export-job-replay"), "corr-b", true);

        assertThat(second).isEqualTo(first);
        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "export-job-replay"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayload_returns409() {
        postAccepted(ACME_TENANT, body("export-job-conflict"), "corr-1", false);

        Map<String, Object> conflicting = body("export-job-conflict");
        conflicting.put("quantity", 2);

        givenBearer(developerToken(ACME_TENANT))
                .body(conflicting)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("IDEMPOTENCY_CONFLICT"));

        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "export-job-conflict"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    @Test
    void differentTenants_mayReuseSameIdempotencyKey() {
        String acme = postAccepted(ACME_TENANT, body("shared-key-1"), "corr-acme", false);
        String beta = postAccepted(BETA_TENANT, body("shared-key-1"), "corr-beta", false);

        assertThat(acme).isNotEqualTo(beta);
        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "shared-key-1"))
                .isEqualTo(1);
        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(BETA_TENANT, "shared-key-1"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(2);
    }

    @Test
    void oneHundredIdenticalSubmissions_resultInOneIngestionAndOneOutbox() {
        String expectedEventId = null;
        for (int i = 0; i < 100; i++) {
            String eventId = postAccepted(ACME_TENANT, body("export-job-storm"), "storm-" + i, i > 0);
            if (expectedEventId == null) {
                expectedEventId = eventId;
            } else {
                assertThat(eventId).isEqualTo(expectedEventId);
            }
        }
        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "export-job-storm"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalSubmissions_resolveToOneLogicalOperation() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                String eventId = givenBearer(developerToken(ACME_TENANT))
                        .body(body("export-job-concurrent"))
                        .when()
                        .post("/usage/events")
                        .then()
                        .statusCode(202)
                        .body("eventId", notNullValue())
                        .extract()
                        .path("eventId");
                accepted.incrementAndGet();
                return eventId;
            }));
        }

        start.countDown();
        Set<String> eventIds = new HashSet<>();
        for (Future<String> future : futures) {
            eventIds.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted.get()).isEqualTo(threads);
        assertThat(eventIds).hasSize(1);
        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "export-job-concurrent"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    @Test
    void concurrentConflictingPayloads_doNotCreateTwoLogicalOperations() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            int quantity = (i % 2) + 1;
            futures.add(pool.submit(() -> {
                start.await();
                Map<String, Object> payload = body("export-job-race-conflict");
                payload.put("quantity", quantity);
                return givenBearer(developerToken(ACME_TENANT))
                        .body(payload)
                        .when()
                        .post("/usage/events")
                        .then()
                        .extract()
                        .statusCode();
            }));
        }

        start.countDown();
        int status202 = 0;
        int status409 = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(60, TimeUnit.SECONDS);
            if (status == 202) {
                status202++;
            } else if (status == 409) {
                status409++;
            } else {
                throw new AssertionError("Unexpected status " + status);
            }
        }
        pool.shutdown();

        assertThat(status202).isGreaterThanOrEqualTo(1);
        assertThat(status202 + status409).isEqualTo(threads);
        assertThat(usageIngestionRepository.countByTenantAndIdempotencyKey(ACME_TENANT, "export-job-race-conflict"))
                .isEqualTo(1);
        assertThat(outboxEventRepository.countAll()).isEqualTo(1);
    }

    private String postAccepted(UUID tenantId, Map<String, Object> body, String correlationId, boolean replay) {
        return givenBearer(developerToken(tenantId))
                .header("X-Correlation-Id", correlationId)
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .body("status", equalTo("ACCEPTED"))
                .body("idempotentReplay", equalTo(replay))
                .extract()
                .path("eventId");
    }

    private static Map<String, Object> body(String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", "2026-08-12T14:30:00Z");
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }
}
