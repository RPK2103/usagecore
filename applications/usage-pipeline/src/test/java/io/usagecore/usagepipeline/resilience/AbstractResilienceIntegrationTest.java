package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import io.usagecore.usagepipeline.support.FixedClockTestConfiguration;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import io.usagecore.usagepipeline.support.TestSecurityConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Dedicated PostgreSQL + Kafka for Phase 10 failure drills.
 * <p>
 * These containers are <strong>not</strong> the shared Phase 5–9 Testcontainers instances.
 * Pause/unpause therefore cannot stall consumers in other cached Spring contexts.
 * Subclasses must set a unique {@code usagecore.kafka.consumer-group} and unique topics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestSecurityConfiguration.class, FixedClockTestConfiguration.class})
@Timeout(value = 3, unit = TimeUnit.MINUTES)
abstract class AbstractResilienceIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore")
            .withCommand("postgres", "-c", "max_connections=200");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @LocalServerPort
    int serverPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    Environment environment;

    @Value("${usagecore.kafka.topics.usage-received}")
    String usageReceivedTopic;

    @Value("${usagecore.kafka.topics.usage-received-dlq}")
    String usageReceivedDlqTopic;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractResilienceIntegrationTest::jdbcUrlWithTimeouts);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "3000");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
        registry.add("usagecore.outbox.publisher.enabled", () -> "false");
        registry.add("usagecore.kafka.publish-timeout", () -> "3s");
        registry.add("usagecore.kafka.consumer-retry.interval-ms", () -> "50");
        registry.add("usagecore.kafka.consumer-retry.max-attempts", () -> "3");
    }

    @BeforeEach
    void configureRestAssuredAndSeed() {
        RestAssured.port = serverPort;
        RestAssured.basePath = "/api/v1";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @AfterEach
    void unpauseDependencies() {
        TestcontainersPause.unpause(KAFKA);
        TestcontainersPause.unpause(POSTGRES);
    }

    protected RequestSpecification givenBearer(UUID tenantId) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(tenantId));
    }

    protected RequestSpecification givenActuator() {
        return RestAssured.given().port(serverPort).basePath("");
    }

    protected String ingestAccepted(UUID tenantId, String idempotencyKey) {
        return givenBearer(tenantId)
                .body(usageBody(idempotencyKey))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .extract()
                .path("eventId");
    }

    protected static Map<String, Object> usageBody(String idempotencyKey) {
        return usageBody(idempotencyKey, Instant.parse("2026-08-12T14:30:00Z"), 1L);
    }

    protected static Map<String, Object> usageBody(String idempotencyKey, Instant occurredAt, long quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", MeterDefinitionFixtureSeeder.PRODUCT_KEY);
        body.put("meterKey", MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT);
        body.put("quantity", quantity);
        body.put("occurredAt", occurredAt.toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    protected void publishUntilPendingDrained(int expectedPublished) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            try {
                outboxPublisher.publishBatch(100);
            } catch (UsagePublicationException ex) {
                throw new AssertionError("outbox publish still failing: " + ex.getMessage(), ex);
            }
            assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
            assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(expectedPublished);
        });
    }

    protected void awaitLedgerAndInbox(UUID eventId, long expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countByEventId("processed_event", eventId)).isEqualTo(expected);
            assertThat(countByEventId("usage_ledger", eventId)).isEqualTo(expected);
        });
    }

    protected long countByEventId(String table, UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    protected long countTable(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    protected long countTableForTenant(String table, UUID tenantId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class,
                tenantId
        );
        return count == null ? 0L : count;
    }

    protected long aggregateValue(UUID tenantId) {
        Long value = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(ua.aggregate_value), 0)
                FROM usage_aggregate ua
                JOIN product p ON p.id = ua.product_id
                WHERE ua.tenant_id = ? AND p.product_key = ? AND ua.meter_key = ?
                """,
                Long.class,
                tenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT
        );
        return value == null ? 0L : value;
    }

    protected OutboxEventRecord requireOutbox(UUID eventId) {
        return outboxEventRepository.findByEventId(eventId).orElseThrow();
    }

    protected int countTopicRecordsContaining(String topic, String eventId, int atLeast, Duration timeout) {
        AtomicInteger found = new AtomicInteger();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "resilience-probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ))) {
            consumer.subscribe(List.of(topic));
            await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> {
                consumer.poll(Duration.ofMillis(400)).forEach(record -> {
                    if (record.value() != null && record.value().contains(eventId)) {
                        found.incrementAndGet();
                    }
                });
                return found.get() >= atLeast;
            });
        }
        return found.get();
    }

    protected void cleanUsageTables() {
        jdbcTemplate.update("DELETE FROM usage_adjustment");
        jdbcTemplate.update("DELETE FROM reconciliation_item");
        jdbcTemplate.update("DELETE FROM reconciliation_run");
        jdbcTemplate.update("DELETE FROM commercial_usage_exception");
        jdbcTemplate.update("DELETE FROM quota_consumption");
        jdbcTemplate.update("DELETE FROM quota_state");
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
    }

    private static String jdbcUrlWithTimeouts() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "connectTimeout=2&socketTimeout=2&loginTimeout=2";
    }
}
