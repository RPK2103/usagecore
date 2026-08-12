package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UnsupportedUsageEventException;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.support.RecordingUsageProcessorConfiguration.RecordingUsageReceivedProcessor;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class UsageIngestionApiIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-12T14:30:00Z");

    @Autowired
    private RecordingUsageReceivedProcessor recordingProcessor;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${usagecore.kafka.topics.usage-received}")
    private String usageReceivedTopic;

    @BeforeEach
    void clearRecording() {
        recordingProcessor.clear();
    }

    @Test
    void noAuthentication_returns401() {
        givenUnauthenticatedJson()
                .body(validBody())
                .when()
                .post("/usage/events")
                .then()
                .statusCode(401)
                .body("errorCode", equalTo("UNAUTHORIZED"));
    }

    @Test
    void tokenWithoutTenant_returns403() {
        givenBearer(TestJwtSupport.developerWithoutTenant())
                .body(validBody())
                .when()
                .post("/usage/events")
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));
    }

    @Test
    void platformAdminWithoutTenant_returns403() {
        givenBearer(TestJwtSupport.platformAdmin())
                .body(validBody())
                .when()
                .post("/usage/events")
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));
    }

    @Test
    void tenantIdInBody_returns400() {
        Map<String, Object> body = validBody();
        body.put("tenantId", ACME_TENANT.toString());

        givenBearer(developerToken(ACME_TENANT))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void unknownRequestProperty_returns400() {
        Map<String, Object> body = validBody();
        body.put("unexpectedField", "nope");

        givenBearer(developerToken(ACME_TENANT))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void quantityNonPositive_returns400() {
        Map<String, Object> body = validBody();
        body.put("quantity", 0);

        givenBearer(developerToken(ACME_TENANT))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void dataPilotAcmeDeveloper_submitsUsage_roundTripsThroughKafka() {
        String correlationId = "datapilot-export-corr-1";

        String eventId = givenBearer(developerToken(ACME_TENANT))
                .header("X-Correlation-Id", correlationId)
                .body(validBody())
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .body("status", equalTo("ACCEPTED"))
                .body("eventId", notNullValue())
                .body("correlationId", equalTo(correlationId))
                .extract()
                .path("eventId");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(recordingProcessor.events()).isNotEmpty()
        );

        EventEnvelope<UsageReceivedPayload> event = recordingProcessor.events().getFirst();
        assertThat(event.eventId().toString()).isEqualTo(eventId);
        assertThat(event.eventType()).isEqualTo(EventTypes.USAGE_RECEIVED);
        assertThat(event.eventVersion()).isEqualTo(EventVersions.V1);
        assertThat(event.tenantId()).isEqualTo(ACME_TENANT);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(event.payload().productKey()).isEqualTo("datapilot-cloud");
        assertThat(event.payload().meterKey()).isEqualTo("scheduled_export");
        assertThat(event.payload().quantity()).isEqualTo(1L);
        assertThat(event.payload().idempotencyKey()).isEqualTo("export-job-174");
        assertThat(event.aggregateId()).isEqualTo(
                UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export")
        );

        // Prove deserialization through event-contracts module types already used above.
        assertThat(event.payload()).isInstanceOf(UsageReceivedPayload.class);
    }

    @Test
    void emittedEvent_usesDeterministicPartitionKey() throws Exception {
        givenBearer(developerToken(ACME_TENANT))
                .header("X-Correlation-Id", "partition-key-corr")
                .body(validBody())
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(recordingProcessor.events()).isNotEmpty()
        );

        String expectedKey = UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export");
        assertThat(recordingProcessor.events().getFirst().aggregateId()).isEqualTo(expectedKey);
    }

    @Test
    void unsupportedEventVersion_failsExplicitlyInConsumer() throws Exception {
        EventEnvelope<UsageReceivedPayload> unsupported = new EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.USAGE_RECEIVED,
                "99",
                OCCURRED_AT,
                ACME_TENANT,
                UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export"),
                "unsupported-version-corr",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "scheduled_export",
                        1L,
                        "export-job-unsupported",
                        "tester"
                )
        );

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all"
        ))) {
            String json = objectMapper.writeValueAsString(unsupported);
            producer.send(new ProducerRecord<>(
                    usageReceivedTopic,
                    UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export"),
                    json
            )).get(10, TimeUnit.SECONDS);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(recordingProcessor.failures())
                        .anyMatch(ex -> ex instanceof UnsupportedUsageEventException
                                && ex.getMessage().contains("Unsupported eventVersion"))
        );
        assertThat(recordingProcessor.events())
                .noneMatch(e -> "export-job-unsupported".equals(e.payload().idempotencyKey()));
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", OCCURRED_AT.toString());
        body.put("idempotencyKey", "export-job-174");
        return body;
    }
}
