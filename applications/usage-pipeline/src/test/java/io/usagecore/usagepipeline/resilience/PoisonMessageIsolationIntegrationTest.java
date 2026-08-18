package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Poison event isolation: A valid, B unsupported, C valid on the same partition.
 */
class PoisonMessageIsolationIntegrationTest extends AbstractResilienceIntegrationTest {

    private static final String CONSUMER_GROUP = "usagecore-resilience-poison";
    private static final String TOPIC = "usagecore.resilience.poison.v1";
    private static final String DLQ = "usagecore.resilience.poison.v1.dlq";
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("usagecore.kafka.topics.usage-received", () -> TOPIC);
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> DLQ);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        cleanUsageTables();
    }

    @Test
    void validPoisonValid_samePartition_healthyEventsApply_poisonGoesToDlq() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String partitionKey = UsagePartitionKey.of(
                tenantId,
                MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT
        );

        UUID eventA = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
        UUID eventB = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
        UUID eventC = UUID.fromString("cccccccc-0000-4000-8000-000000000003");

        EventEnvelope<UsageReceivedPayload> validA = envelope(eventA, tenantId, partitionKey, EventVersions.V1, "poison-a");
        EventEnvelope<UsageReceivedPayload> poisonB = envelope(eventB, tenantId, partitionKey, "99", "poison-b");
        EventEnvelope<UsageReceivedPayload> validC = envelope(eventC, tenantId, partitionKey, EventVersions.V1, "poison-c");

        double dlqBefore = counterValue("usagecore.usage.dlq", "reason", "non_retryable");

        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "resilience-dlq-assert-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ))) {
            dlqConsumer.subscribe(List.of(DLQ));

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                    ProducerConfig.ACKS_CONFIG, "all"
            ))) {
                producer.send(new ProducerRecord<>(TOPIC, partitionKey, objectMapper.writeValueAsString(validA)))
                        .get(10, TimeUnit.SECONDS);
                producer.send(new ProducerRecord<>(TOPIC, partitionKey, objectMapper.writeValueAsString(poisonB)))
                        .get(10, TimeUnit.SECONDS);
                producer.send(new ProducerRecord<>(TOPIC, partitionKey, objectMapper.writeValueAsString(validC)))
                        .get(10, TimeUnit.SECONDS);
            }

            AtomicInteger poisonDlq = new AtomicInteger();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var records = KafkaTestUtils.getRecords(dlqConsumer, Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> rec : records.records(DLQ)) {
                    if (rec.value() != null && rec.value().contains(eventB.toString())) {
                        assertThat(rec.value()).contains("\"eventVersion\":\"99\"");
                        poisonDlq.incrementAndGet();
                    }
                }
                assertThat(poisonDlq.get()).isGreaterThanOrEqualTo(1);
            });
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countByEventId("usage_ledger", eventA)).isEqualTo(1);
            assertThat(countByEventId("processed_event", eventA)).isEqualTo(1);
            assertThat(countByEventId("usage_ledger", eventC)).isEqualTo(1);
            assertThat(countByEventId("processed_event", eventC)).isEqualTo(1);
        });

        assertThat(countByEventId("usage_ledger", eventB)).isZero();
        assertThat(countByEventId("processed_event", eventB)).isZero();
        assertThat(aggregateValue(tenantId)).isEqualTo(2L);
        assertThat(counterValue("usagecore.usage.dlq", "reason", "non_retryable")).isGreaterThan(dlqBefore);
    }

    private EventEnvelope<UsageReceivedPayload> envelope(
            UUID eventId,
            UUID tenantId,
            String partitionKey,
            String version,
            String idempotencyKey
    ) {
        return new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                version,
                OCCURRED,
                tenantId,
                partitionKey,
                "corr-" + idempotencyKey,
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        MeterDefinitionFixtureSeeder.PRODUCT_KEY,
                        MeterDefinitionFixtureSeeder.METER_SCHEDULED_EXPORT,
                        1L,
                        idempotencyKey,
                        "tester"
                )
        );
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
