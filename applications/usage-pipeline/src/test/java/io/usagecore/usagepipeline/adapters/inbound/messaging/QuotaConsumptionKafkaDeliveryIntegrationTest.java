package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
import io.usagecore.usagepipeline.support.FixedClockTestConfiguration;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import io.usagecore.usagepipeline.support.TestSecurityConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 6C end-to-end: ACCEPTED consume → transactional outbox → Kafka → reporting aggregates.
 * <p>
 * Uses dedicated Postgres/Kafka containers and unique client-ids so this context cannot
 * pollute or disturb other integration-test Spring contexts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestSecurityConfiguration.class, FixedClockTestConfiguration.class})
class QuotaConsumptionKafkaDeliveryIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Instant OCCURRED = Instant.parse("2026-08-13T10:00:00Z");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant JAN_START = Instant.parse("2026-01-01T00:00:00Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore-quota-kafka")
            .withUsername("usagecore")
            .withPassword("usagecore");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.client-id", () -> "usagecore-quota-delivery-consumer");
        registry.add("spring.kafka.producer.client-id", () -> "usagecore-quota-delivery-producer");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
        registry.add("usagecore.kafka.topics.usage-received", () -> "usagecore.usage.received.v1");
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> "usagecore.usage.received.v1.dlq");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-quota-kafka-delivery");
        registry.add("usagecore.outbox.publisher.enabled", () -> "false");
        registry.add("usagecore.kafka.consumer-retry.interval-ms", () -> "50");
        registry.add("usagecore.kafka.consumer-retry.max-attempts", () -> "2");
    }

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private UsageWindowAggregateRepository usageWindowAggregateRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private QuotaCommercialFixtureSeeder seeder;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        jdbc.update("DELETE FROM usage_window_aggregate");
        jdbc.update("DELETE FROM usage_aggregate");
        jdbc.update("DELETE FROM usage_ledger");
        jdbc.update("DELETE FROM processed_event");
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
    void acceptedConsume_publishBatch_eventuallyUpdatesWindowAggregate() {
        String eventId = givenBearer(developerToken(ACME))
                .body(body(15, "quota-kafka-delivery"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"))
                .body("reason", equalTo("WITHIN_QUOTA"))
                .body("consumed", equalTo(15))
                .body("eventId", notNullValue())
                .extract()
                .path("eventId");

        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = ? AND event_id = ?",
                Long.class,
                OutboxStatus.PENDING.name(),
                UUID.fromString(eventId)
        );
        assertThat(pending).isEqualTo(1);

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long ledger = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM usage_ledger WHERE event_id = ?",
                    Long.class,
                    UUID.fromString(eventId)
            );
            assertThat(ledger).isEqualTo(1);

            UsageWindowAggregateRecord window = usageWindowAggregateRepository
                    .findByTenantProductMeterAndWindow(
                            ACME,
                            MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                            MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                            AUG_START,
                            SEP_START
                    )
                    .orElseThrow();
            assertThat(window.aggregationType()).isEqualTo(AggregationType.SUM);
            assertThat(window.aggregateValue()).isEqualTo(15L);
            assertThat(window.eventCount()).isEqualTo(1L);
        });

        assertThat(seeder.quotaConsumed(
                ACME, MeterDefinitionFixtureSeeder.METER_API_REQUESTS, AUG_START, SEP_START
        )).isEqualTo(15L);
    }

    private RequestSpecification givenBearer(String token) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + token);
    }

    private String developerToken(UUID tenantId) {
        return TestJwtSupport.developer(tenantId);
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
}
