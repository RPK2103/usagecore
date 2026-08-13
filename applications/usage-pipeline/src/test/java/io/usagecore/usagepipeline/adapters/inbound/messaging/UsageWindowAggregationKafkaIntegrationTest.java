package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Kafka path updates event-time window aggregates.
 */
class UsageWindowAggregationKafkaIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-window-kafka-test");
    }

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private UsageWindowAggregateRepository usageWindowAggregateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAndSeed() {
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void httpOutboxKafkaPath_updatesAugustWindowAggregate() {
        submit(10, "window-kafka-1");
        submit(25, "window-kafka-2");
        submit(5, "window-kafka-3");

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(3);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            UsageWindowAggregateRecord window = usageWindowAggregateRepository
                    .findByTenantProductMeterAndWindow(
                            ACME,
                            "datapilot-cloud",
                            "api_requests",
                            AUG_START,
                            SEP_START
                    )
                    .orElseThrow();
            assertThat(window.aggregationType()).isEqualTo(AggregationType.SUM);
            assertThat(window.aggregateValue()).isEqualTo(40L);
            assertThat(window.eventCount()).isEqualTo(3L);
        });

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
                .body("eventCount", equalTo(3));
    }

    private void submit(long quantity, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "api_requests");
        body.put("quantity", quantity);
        body.put("occurredAt", OCCURRED.toString());
        body.put("idempotencyKey", idempotencyKey);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202);
    }
}
