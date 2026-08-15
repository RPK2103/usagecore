package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.restassured.RestAssured;
import io.usagecore.usagepipeline.adapters.observability.W3cTraceContext;
import io.usagecore.usagepipeline.application.observability.UsagePipelineMetrics;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.quota.QuotaReasonCodes;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@AutoConfigureObservability
class UsagePipelineObservabilityIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID OBS_TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000009");
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "tenantId",
            "eventId",
            "correlationId",
            "idempotencyKey",
            "contractId",
            "principalId",
            "commercialPeriodId",
            "reconciliationRunId",
            "adjustmentId"
    );

    @LocalServerPort
    private int serverPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${usagecore.kafka.topics.usage-received}")
    private String usageReceivedTopic;

    private QuotaCommercialFixtureSeeder seeder;

    @DynamicPropertySource
    static void observabilityKafkaGroup(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-obs-test");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM quota_consumption");
        jdbc.update("DELETE FROM quota_state");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM usage_ingestion");
        seeder = new QuotaCommercialFixtureSeeder(jdbc);
        seeder.ensureTenant(OBS_TENANT, "obs-tenant");
        seeder.ensureCatalogue();
        seeder.seedActivatedEntitlement(
                OBS_TENANT,
                "obs-dp-contract",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                100L
        );
    }

    @Test
    void healthReadinessIgnoresKafka_prometheusAndJwtStayCorrect() {
        assertThat(environment.getProperty("management.health.kafka.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .contains("db")
                .doesNotContain("kafka");

        RestAssured.given().port(serverPort).basePath("").get("/actuator/health")
                .then().statusCode(200).body("status", equalTo("UP"));
        RestAssured.given().port(serverPort).basePath("").get("/actuator/health/liveness")
                .then().statusCode(200);
        RestAssured.given().port(serverPort).basePath("").get("/actuator/health/readiness")
                .then().statusCode(200).body("status", equalTo("UP"));

        String prometheus = RestAssured.given().port(serverPort).basePath("").get("/actuator/prometheus")
                .then().statusCode(200).extract().asString();
        assertThat(prometheus).contains("jvm_memory_used_bytes");
        assertThat(prometheus).contains("hikaricp_connections");

        RestAssured.given().port(serverPort).basePath("").get("/actuator/env")
                .then().statusCode(HttpStatus.UNAUTHORIZED.value());

        givenUnauthenticatedJson()
                .body(ingestBody("obs-unauth"))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(401);
    }

    @Test
    void httpCorrelationAndTraceEvidenceReachOutboxAndKafkaHeaders() throws Exception {
        String correlationId = "test-correlation-123";
        String eventId = givenBearer(developerToken(OBS_TENANT))
                .header("X-Correlation-Id", correlationId)
                .body(ingestBody("obs-trace-1"))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("correlationId", equalTo(correlationId))
                .extract()
                .path("eventId");

        OutboxEventRecord pending = outboxEventRepository.findByEventId(UUID.fromString(eventId)).orElseThrow();
        JsonNode envelope = objectMapper.readTree(pending.serializedEnvelope());
        assertThat(envelope.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(envelope.get("traceId").isNull()).isFalse();
        String storedTrace = envelope.get("traceId").asText();
        assertThat(storedTrace).isNotBlank();
        assertThat(W3cTraceContext.isTraceparent(storedTrace) || W3cTraceContext.isHexTraceId(storedTrace)).isTrue();

        assertThat(meterRegistry.find(UsagePipelineMetrics.OUTBOX_PENDING).gauge()).isNotNull();
        assertThat(meterRegistry.find(UsagePipelineMetrics.OUTBOX_PENDING).gauge().value()).isGreaterThanOrEqualTo(1.0d);

        int published = outboxPublisher.publishBatch(10);
        assertThat(published).isEqualTo(1);
        assertThat(outboxEventRepository.findByEventId(UUID.fromString(eventId)).orElseThrow().status())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(meterRegistry.find(UsagePipelineMetrics.OUTBOX_PUBLISH)
                .tag("result", UsagePipelineMetrics.RESULT_SUCCESS).counter().count()).isGreaterThanOrEqualTo(1.0d);

        String prometheus = RestAssured.given().port(serverPort).basePath("").get("/actuator/prometheus")
                .then().statusCode(200).extract().asString();
        assertThat(prometheus).contains("usagecore_outbox_publish_total");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "obs-trace-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ))) {
            consumer.subscribe(java.util.List.of(usageReceivedTopic));
            java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, String>> found =
                    new java.util.concurrent.atomic.AtomicReference<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    if (rec.value() != null && rec.value().contains(eventId)) {
                        found.set(rec);
                        return true;
                    }
                }
                return false;
            });
            ConsumerRecord<String, String> record = found.get();
            assertThat(header(record, "correlationId")).isEqualTo(correlationId);
            String traceparent = header(record, "traceparent");
            assertThat(traceparent).isNotBlank();
            assertThat(W3cTraceContext.isTraceparent(traceparent)).isTrue();
        }
    }

    @Test
    void quotaDecisionsAndPrometheusExportUseBoundedLabels() {
        double acceptedBefore = counter(
                UsagePipelineMetrics.QUOTA_DECISIONS,
                "decision", "ACCEPTED",
                "reason", QuotaReasonCodes.WITHIN_QUOTA
        );
        givenBearer(developerToken(OBS_TENANT))
                .body(consumeBody(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 10, "obs-quota-ok"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ACCEPTED"));
        assertThat(counter(
                UsagePipelineMetrics.QUOTA_DECISIONS,
                "decision", "ACCEPTED",
                "reason", QuotaReasonCodes.WITHIN_QUOTA
        )).isEqualTo(acceptedBefore + 1.0d);

        seeder.seedQuotaConsumed(
                OBS_TENANT,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                100L,
                100L
        );
        double rejectedBefore = counter(
                UsagePipelineMetrics.QUOTA_DECISIONS,
                "decision", "REJECTED",
                "reason", QuotaReasonCodes.QUOTA_EXHAUSTED
        );
        givenBearer(developerToken(OBS_TENANT))
                .body(consumeBody(MeterDefinitionFixtureSeeder.METER_API_REQUESTS, 1, "obs-quota-ex"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(200)
                .body("decision", equalTo("REJECTED"))
                .body("reason", equalTo(QuotaReasonCodes.QUOTA_EXHAUSTED));
        assertThat(counter(
                UsagePipelineMetrics.QUOTA_DECISIONS,
                "decision", "REJECTED",
                "reason", QuotaReasonCodes.QUOTA_EXHAUSTED
        )).isEqualTo(rejectedBefore + 1.0d);

        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("usagecore.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(FORBIDDEN_TAG_KEYS).doesNotContain(tag.getKey());
            }
        }

        String prometheus = RestAssured.given().port(serverPort).basePath("").get("/actuator/prometheus")
                .then().statusCode(200).extract().asString();
        assertThat(prometheus).contains("usagecore_quota_decisions_total");
        assertThat(prometheus).contains("usagecore_outbox_pending");
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value());
    }

    private static Map<String, Object> ingestBody(String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", Instant.parse("2026-08-12T14:30:00Z").toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    private static Map<String, Object> consumeBody(String meterKey, long quantity, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", MeterDefinitionFixtureSeeder.PRODUCT_KEY);
        body.put("meterKey", meterKey);
        body.put("quantity", quantity);
        body.put("occurredAt", Instant.parse("2026-08-13T10:00:00Z").toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }
}
