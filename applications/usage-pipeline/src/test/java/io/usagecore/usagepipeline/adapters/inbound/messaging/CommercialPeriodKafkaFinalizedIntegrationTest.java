package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.support.CommercialPeriodFixtureSeeder;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Kafka path classifying a finalized-period UsageReceived into commercial quarantine.
 */
class CommercialPeriodKafkaFinalizedIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("dddddddd-1111-1111-1111-111111111111");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-20T12:00:00Z");

    @DynamicPropertySource
    static void kafkaConsumerProps(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-phase7-finalized-kafka");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${usagecore.kafka.topics.usage-received}")
    private String usageReceivedTopic;

    private CommercialPeriodFixtureSeeder periods;

    @BeforeEach
    void setUp() {
        periods = new CommercialPeriodFixtureSeeder(jdbc);
        periods.clearCommercialTables();
        jdbc.update("DELETE FROM usage_window_aggregate");
        jdbc.update("DELETE FROM usage_aggregate");
        jdbc.update("DELETE FROM usage_ledger");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM usage_ingestion");

        QuotaCommercialFixtureSeeder commercial = new QuotaCommercialFixtureSeeder(jdbc);
        commercial.ensureTenant(ACME, "acme-kafka-finalized");
        UUID productId = commercial.ensureCatalogue();
        periods.insertPeriod(ACME, productId, AUG_START, SEP_START, "FINALIZED");
    }

    @Test
    void kafkaUsageReceived_finalizedPeriod_quarantinesWithoutAggregateMutation() throws Exception {
        UUID eventId = UUID.randomUUID();
        String partitionKey = UsagePartitionKey.of(
                ACME,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_API_REQUESTS
        );
        EventEnvelope<UsageReceivedPayload> envelope = new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                ACME,
                partitionKey,
                "corr-kafka-finalized",
                null,
                null,
                Instant.parse("2026-09-05T12:00:00Z"),
                new UsageReceivedPayload(
                        MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                        MeterDefinitionFixtureSeeder.METER_API_REQUESTS,
                        17L,
                        "kafka-finalized-1",
                        "svc-kafka"
                )
        );

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(
                    usageReceivedTopic,
                    partitionKey,
                    objectMapper.writeValueAsString(envelope)
            )).get(30, TimeUnit.SECONDS);
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(count("processed_event", eventId)).isEqualTo(1);
            assertThat(count("usage_ledger", eventId)).isEqualTo(1);
            assertThat(periods.exceptionCountForEvent(eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT reason FROM commercial_usage_exception WHERE event_id = ?",
                    String.class,
                    eventId
            )).isEqualTo("PERIOD_FINALIZED");
            assertThat(aggregateCount()).isZero();
            assertThat(windowAggregateCount()).isZero();
        });
    }

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private long count(String table, UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    private long aggregateCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_aggregate WHERE tenant_id = ?",
                Long.class,
                ACME
        );
        return count == null ? 0L : count;
    }

    private long windowAggregateCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_window_aggregate WHERE tenant_id = ?",
                Long.class,
                ACME
        );
        return count == null ? 0L : count;
    }
}
