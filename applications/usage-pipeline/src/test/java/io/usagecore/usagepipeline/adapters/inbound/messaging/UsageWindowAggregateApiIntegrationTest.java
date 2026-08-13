package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tenant-scoped window aggregate HTTP read + cross-tenant isolation.
 */
class UsageWindowAggregateApiIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-window-api-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAndSeed() {
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void currentWindow_returnsTenantScopedAugustAggregate() {
        process(ACME, 40L, "window-api-acme");

        RestAssured.given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME))
                .when()
                .get("/usage/aggregates/{productKey}/{meterKey}/windows/current", "datapilot-cloud", "api_requests")
                .then()
                .statusCode(200)
                .body("productKey", equalTo("datapilot-cloud"))
                .body("meterKey", equalTo("api_requests"))
                .body("aggregationType", equalTo("SUM"))
                .body("windowStart", equalTo("2026-08-01T00:00:00Z"))
                .body("windowEnd", equalTo("2026-09-01T00:00:00Z"))
                .body("value", equalTo(40))
                .body("eventCount", equalTo(1));
    }

    @Test
    void crossTenant_cannotReadOtherTenantWindowOrLifetimeAggregate() {
        process(ACME, 100L, "window-api-acme-iso");
        process(GLOBEX, 500L, "window-api-globex-iso");

        RestAssured.given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME))
                .when()
                .get("/usage/aggregates/{productKey}/{meterKey}/windows/current", "datapilot-cloud", "api_requests")
                .then()
                .statusCode(200)
                .body("value", equalTo(100));

        RestAssured.given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(GLOBEX))
                .when()
                .get("/usage/aggregates/{productKey}/{meterKey}/windows/current", "datapilot-cloud", "api_requests")
                .then()
                .statusCode(200)
                .body("value", equalTo(500));

        RestAssured.given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME))
                .when()
                .get("/usage/aggregates/{productKey}/{meterKey}", "datapilot-cloud", "api_requests")
                .then()
                .statusCode(200)
                .body("value", equalTo(100));

        RestAssured.given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(GLOBEX))
                .when()
                .get("/usage/aggregates/{productKey}/{meterKey}", "datapilot-cloud", "api_requests")
                .then()
                .statusCode(200)
                .body("value", equalTo(500));
    }

    private void process(UUID tenantId, long quantity, String idempotencyKey) {
        usageReceivedProcessor.process(new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                Instant.parse("2026-08-12T14:30:00Z"),
                tenantId,
                UsagePartitionKey.of(tenantId, "datapilot-cloud", "api_requests"),
                "corr-window-api",
                null,
                null,
                Instant.parse("2026-08-12T15:00:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "api_requests",
                        quantity,
                        idempotencyKey,
                        "svc"
                )
        ));
    }
}
