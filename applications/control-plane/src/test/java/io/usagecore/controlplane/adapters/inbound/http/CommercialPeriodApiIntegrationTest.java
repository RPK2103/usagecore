package io.usagecore.controlplane.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.usagecore.controlplane.support.TestJwtSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CommercialPeriodApiIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createOpenAndValidLifecycleTransitionsWithAudit() {
        UUID tenantId = UUID.fromString(createTenant("cp-period-acme", "Acme Period"));
        String productId = createProduct("datapilot-period-api", "DataPilot Period API");

        String periodId = givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201)
                .body("status", equalTo("OPEN"))
                .body("tenantId", equalTo(tenantId.toString()))
                .body("productId", equalTo(productId))
                .body("closingStartedAt", nullValue())
                .body("finalizedAt", nullValue())
                .extract()
                .path("id");

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("CLOSING"))
                .body("closingStartedAt", notNullValue());

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/reconciling",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("RECONCILING"))
                .body("reconcilingStartedAt", notNullValue());

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/finalize",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("FINALIZED"))
                .body("finalizedAt", notNullValue())
                .body("finalizedBy", equalTo("platform-admin"));

        givenJson()
                .when()
                .get("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200)
                .body("status", equalTo("FINALIZED"));

        Integer transitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commercial_period_transition WHERE commercial_period_id = ?",
                Integer.class,
                UUID.fromString(periodId)
        );
        assertThat(transitions).isEqualTo(3);

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM commercial_period_transition
                WHERE commercial_period_id = ? AND from_status = 'RECONCILING' AND to_status = 'FINALIZED'
                """,
                Integer.class,
                UUID.fromString(periodId)
        )).isEqualTo(1);
    }

    @Test
    void invalidTransitionsReturn409() {
        UUID tenantId = UUID.fromString(createTenant("cp-period-invalid", "Invalid Period"));
        String productId = createProduct("datapilot-period-invalid", "DataPilot Invalid");

        String periodId = givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/finalize",
                        tenantId, productId, periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/reconciling",
                        tenantId, productId, periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200);

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        tenantId, productId, periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/reconciling",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200);

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/finalize",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200);

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        tenantId, productId, periodId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    void overlappingPeriodsRejected() {
        UUID tenantId = UUID.fromString(createTenant("cp-period-overlap", "Overlap Period"));
        String productId = createProduct("datapilot-period-overlap", "DataPilot Overlap");

        givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201);

        givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-15T00:00:00Z",
                          "periodEnd": "2026-09-15T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("COMMERCIAL_INTERVAL_CONFLICT"));
    }

    @Test
    void adjacentPeriodsAllowed() {
        UUID tenantId = UUID.fromString(createTenant("cp-period-adjacent", "Adjacent Period"));
        String productId = createProduct("datapilot-period-adjacent", "DataPilot Adjacent");

        givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201);

        givenJson()
                .body("""
                        {
                          "periodStart": "2026-09-01T00:00:00Z",
                          "periodEnd": "2026-10-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201);
    }

    @Test
    void concurrentOverlappingCreates_oneWinner() throws Exception {
        UUID tenantId = UUID.fromString(createTenant("cp-period-conc", "Concurrent Period"));
        String productId = createProduct("datapilot-period-conc", "DataPilot Concurrent");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(() -> createConcurrently(
                    tenantId, productId,
                    "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z",
                    ready, start, success, failure
            ));
            Future<?> b = executor.submit(() -> createConcurrently(
                    tenantId, productId,
                    "2026-08-15T00:00:00Z", "2026-09-15T00:00:00Z",
                    ready, start, success, failure
            ));
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(1);
    }

    @Test
    void concurrentSameTransition_oneWinner() throws Exception {
        UUID tenantId = UUID.fromString(createTenant("cp-period-tx", "Transition Period"));
        String productId = createProduct("datapilot-period-tx", "DataPilot Transition");

        String periodId = givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(() -> transitionConcurrently(
                    tenantId, productId, periodId, "reconciling", ready, start, success, failure
            ));
            Future<?> b = executor.submit(() -> transitionConcurrently(
                    tenantId, productId, periodId, "reconciling", ready, start, success, failure
            ));
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(failure.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM commercial_period WHERE id = ?",
                String.class,
                UUID.fromString(periodId)
        )).isEqualTo("RECONCILING");
    }

    @Test
    void tenantIsolationAndAuthorization() {
        UUID acme = UUID.fromString(createTenant("cp-period-acme-iso", "Acme Iso"));
        UUID globex = UUID.fromString(createTenant("cp-period-globex-iso", "Globex Iso"));
        String productId = createProduct("datapilot-period-iso", "DataPilot Iso");

        String acmePeriod = givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", acme, productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", globex, productId)
                .then()
                .statusCode(201);

        givenBearer(TestJwtSupport.billingOperator(acme))
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        acme, productId, acmePeriod)
                .then()
                .statusCode(200)
                .body("status", equalTo("CLOSING"));

        givenBearer(TestJwtSupport.billingOperator(globex))
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        acme, productId, acmePeriod)
                .then()
                .statusCode(403);

        givenBearer(TestJwtSupport.developer(acme))
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/reconciling",
                        acme, productId, acmePeriod)
                .then()
                .statusCode(403);

        givenBearer(TestJwtSupport.auditor(acme))
                .when()
                .get("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}",
                        acme, productId, acmePeriod)
                .then()
                .statusCode(200)
                .body("status", equalTo("CLOSING"));
    }

    private void createConcurrently(
            UUID tenantId,
            String productId,
            String start,
            String end,
            CountDownLatch ready,
            CountDownLatch startLatch,
            AtomicInteger success,
            AtomicInteger failure
    ) {
        ready.countDown();
        try {
            startLatch.await(10, TimeUnit.SECONDS);
            int status = givenJson()
                    .body("""
                            {
                              "periodStart": "%s",
                              "periodEnd": "%s"
                            }
                            """.formatted(start, end))
                    .when()
                    .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                    .then()
                    .extract()
                    .statusCode();
            if (status == 201) {
                success.incrementAndGet();
            } else {
                failure.incrementAndGet();
            }
        } catch (Exception ex) {
            failure.incrementAndGet();
        }
    }

    private void transitionConcurrently(
            UUID tenantId,
            String productId,
            String periodId,
            String transition,
            CountDownLatch ready,
            CountDownLatch startLatch,
            AtomicInteger success,
            AtomicInteger failure
    ) {
        ready.countDown();
        try {
            startLatch.await(10, TimeUnit.SECONDS);
            int status = givenJson()
                    .when()
                    .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/{transition}",
                            tenantId, productId, periodId, transition)
                    .then()
                    .extract()
                    .statusCode();
            if (status == 200) {
                success.incrementAndGet();
            } else {
                failure.incrementAndGet();
            }
        } catch (Exception ex) {
            failure.incrementAndGet();
        }
    }

    private String createProduct(String key, String name) {
        return givenJson()
                .body("""
                        {
                          "productKey": "%s",
                          "name": "%s"
                        }
                        """.formatted(key, name))
                .when()
                .post("/products")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createTenant(String key, String displayName) {
        return givenJson()
                .body("""
                        {
                          "tenantKey": "%s",
                          "displayName": "%s"
                        }
                        """.formatted(key, displayName))
                .when()
                .post("/tenants")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
