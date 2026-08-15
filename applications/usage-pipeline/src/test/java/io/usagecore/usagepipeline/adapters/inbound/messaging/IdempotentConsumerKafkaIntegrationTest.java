package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRecord;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Kafka → real idempotent consumer → PostgreSQL ledger/inbox evidence.
 */
class IdempotentConsumerKafkaIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-12T14:30:00Z");

    @DynamicPropertySource
    static void kafkaConsumerProps(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-kafka-ledger-test");
    }

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private UsageLedgerRepository usageLedgerRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${usagecore.kafka.topics.usage-received}")
    private String usageReceivedTopic;

    @Value("${usagecore.kafka.topics.usage-received-dlq}")
    private String usageReceivedDlqTopic;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void publishUsageReceived_realConsumerWritesLedgerAndInbox() {
        String correlationId = "kafka-ledger-corr-1";

        String eventId = RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME_TENANT))
                .header("X-Correlation-Id", correlationId)
                .body(validBody("export-job-kafka-ledger"))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .body("status", equalTo("ACCEPTED"))
                .body("eventId", notNullValue())
                .extract()
                .path("eventId");

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        UUID id = UUID.fromString(eventId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(usageLedgerRepository.countByEventId(id)).isEqualTo(1);
            assertThat(countProcessed(id)).isEqualTo(1);
        });

        UsageLedgerRecord ledger = usageLedgerRepository.findByEventId(id).orElseThrow();
        assertThat(ledger.tenantId()).isEqualTo(ACME_TENANT);
        assertThat(ledger.productKey()).isEqualTo("datapilot-cloud");
        assertThat(ledger.meterKey()).isEqualTo("scheduled_export");
        assertThat(ledger.quantity()).isEqualTo(1L);
        assertThat(ledger.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(ledger.idempotencyKey()).isEqualTo("export-job-kafka-ledger");
        assertThat(ledger.correlationId()).isEqualTo(correlationId);
    }

    @Test
    void republishSameStoredEvent_noSecondBusinessEffect() throws Exception {
        String eventId = RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME_TENANT))
                .body(validBody("export-job-kafka-redeliver"))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .extract()
                .path("eventId");

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        UUID id = UUID.fromString(eventId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(usageLedgerRepository.countByEventId(id)).isEqualTo(1)
        );

        String envelopeJson = jdbcTemplate.queryForObject(
                "SELECT serialized_envelope FROM outbox_event WHERE event_id = ?",
                String.class,
                id
        );
        String partitionKey = UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export");

        // Simulated Kafka redelivery of the same stored event (same eventId).
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all"
        ))) {
            producer.send(new ProducerRecord<>(usageReceivedTopic, partitionKey, envelopeJson))
                    .get(10, TimeUnit.SECONDS);
        }

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(usageLedgerRepository.countByEventId(id)).isEqualTo(1);
            assertThat(countProcessed(id)).isEqualTo(1);
            assertThat(usageLedgerRepository.countAll()).isEqualTo(1);
            assertThat(countProcessedAll()).isEqualTo(1);
        });
    }

    @Test
    void unsupportedEventVersion_landsInDlq_withoutLedgerEffect() throws Exception {
        UUID poisonEventId = UUID.fromString("90909090-9090-9090-9090-909090909090");
        EventEnvelope<UsageReceivedPayload> unsupported = new EventEnvelope<>(
                poisonEventId,
                EventTypes.USAGE_RECEIVED,
                "99",
                OCCURRED_AT,
                ACME_TENANT,
                UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export"),
                "unsupported-version-dlq",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "scheduled_export",
                        1L,
                        "export-job-unsupported-dlq",
                        "tester"
                )
        );

        String json = objectMapper.writeValueAsString(unsupported);
        String partitionKey = UsagePartitionKey.of(ACME_TENANT, "datapilot-cloud", "scheduled_export");

        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ))) {
            dlqConsumer.subscribe(java.util.List.of(usageReceivedDlqTopic));

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                    ProducerConfig.ACKS_CONFIG, "all"
            ))) {
                producer.send(new ProducerRecord<>(usageReceivedTopic, partitionKey, json))
                        .get(10, TimeUnit.SECONDS);
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var records = KafkaTestUtils.getRecords(dlqConsumer, Duration.ofSeconds(2));
                boolean found = false;
                for (ConsumerRecord<String, String> rec : records.records(usageReceivedDlqTopic)) {
                    if (rec.value() != null && rec.value().contains(poisonEventId.toString())) {
                        assertThat(rec.value()).contains("\"eventVersion\":\"99\"");
                        found = true;
                        break;
                    }
                }
                assertThat(found).isTrue();
            });
        }

        assertThat(usageLedgerRepository.countByEventId(poisonEventId)).isZero();
        assertThat(countProcessed(poisonEventId)).isZero();
    }

    private long countProcessed(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private long countProcessedAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Long.class);
        return count == null ? 0L : count;
    }

    private static Map<String, Object> validBody(String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", OCCURRED_AT.toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }
}
