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
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Kafka path: outbox publish → listener → inbox + ledger + aggregate.
 */
class UsageAggregationKafkaIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-agg-kafka-test");
    }

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private UsageAggregateRepository usageAggregateRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${usagecore.kafka.topics.usage-received}")
    private String usageReceivedTopic;

    @Value("${usagecore.kafka.topics.usage-received-dlq}")
    private String usageReceivedDlqTopic;

    @BeforeEach
    void cleanAndSeed() {
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void httpOutboxKafkaPath_updatesAggregate() {
        submit("api_requests", 10, "agg-kafka-1");
        submit("api_requests", 25, "agg-kafka-2");
        submit("api_requests", 5, "agg-kafka-3");

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(3);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            UsageAggregateRecord aggregate = usageAggregateRepository
                    .findByTenantProductKeyAndMeterKey(ACME, "datapilot-cloud", "api_requests")
                    .orElseThrow();
            assertThat(aggregate.aggregationType()).isEqualTo(AggregationType.SUM);
            assertThat(aggregate.aggregateValue()).isEqualTo(40L);
            assertThat(aggregate.eventCount()).isEqualTo(3L);
        });

        RestAssured.given()
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.developer(ACME))
                .when()
                .get("/usage/aggregates/{productKey}/{meterKey}", "datapilot-cloud", "api_requests")
                .then()
                .statusCode(200)
                .body("productKey", equalTo("datapilot-cloud"))
                .body("meterKey", equalTo("api_requests"))
                .body("aggregationType", equalTo("SUM"))
                .body("value", equalTo(40))
                .body("eventCount", equalTo(3));
    }

    @Test
    void unknownMeter_landsInDlq_withoutCanonicalState() throws Exception {
        UUID poisonEventId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-111111111111");
        EventEnvelope<UsageReceivedPayload> unknown = new EventEnvelope<>(
                poisonEventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                ACME,
                UsagePartitionKey.of(ACME, "datapilot-cloud", "no_such_meter"),
                "unknown-meter-dlq",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "no_such_meter",
                        1L,
                        "unknown-meter-dlq-key",
                        "tester"
                )
        );

        String json = objectMapper.writeValueAsString(unknown);
        String partitionKey = UsagePartitionKey.of(ACME, "datapilot-cloud", "no_such_meter");

        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-unknown-meter-" + UUID.randomUUID(),
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

            ConsumerRecord<String, String> dlqRecord = null;
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            while (dlqRecord == null && System.currentTimeMillis() < deadline) {
                var records = dlqConsumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains(poisonEventId.toString())) {
                        dlqRecord = record;
                        break;
                    }
                }
            }
            assertThat(dlqRecord).isNotNull();
            assertThat(dlqRecord.value()).contains("no_such_meter");
            assertThat(dlqRecord.value()).contains(poisonEventId.toString());
        }

        Long processed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Long.class,
                poisonEventId
        );
        Long ledger = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger WHERE event_id = ?",
                Long.class,
                poisonEventId
        );
        assertThat(processed).isZero();
        assertThat(ledger).isZero();
        assertThat(usageAggregateRepository.countAll()).isZero();
    }

    private void submit(String meterKey, long quantity, String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", meterKey);
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
                .statusCode(202)
                .body("eventId", notNullValue());
    }
}
