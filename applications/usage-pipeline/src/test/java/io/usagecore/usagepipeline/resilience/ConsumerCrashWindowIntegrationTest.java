package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import java.time.Duration;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Consumer crash windows: rollback before commit, and DB commit / offset acknowledgement gap.
 */
@Import(ConsumerCrashWindowTestConfiguration.class)
class ConsumerCrashWindowIntegrationTest extends AbstractResilienceIntegrationTest {

    private static final String CONSUMER_GROUP = "usagecore-resilience-consumer-crash";
    private static final String TOPIC = "usagecore.resilience.consumer-crash.v1";
    private static final String DLQ = "usagecore.resilience.consumer-crash.v1.dlq";

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("usagecore.kafka.topics.usage-received", () -> TOPIC);
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> DLQ);
    }

    @Autowired
    private ConsumerCrashWindowTestConfiguration.Gate gate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        gate.reset();
        cleanUsageTables();
    }

    @Test
    void failBeforeDbCommit_rollsBackThenRedeliveryAppliesOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String eventId = ingestAccepted(tenantId, "pre-commit-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);

        gate.armFailBeforeLedgerOnce();
        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        assertThat(gate.beforeLedgerHit.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(countByEventId("processed_event", id)).isZero();
        assertThat(countByEventId("usage_ledger", id)).isZero();
        assertThat(aggregateValue(tenantId)).isZero();

        gate.releaseBeforeLedgerFailure.countDown();

        awaitLedgerAndInbox(id, 1);
        assertThat(aggregateValue(tenantId)).isEqualTo(1L);
        assertThat(countTableForTenant("usage_ledger", tenantId)).isEqualTo(1);
        assertThat(countTableForTenant("processed_event", tenantId)).isEqualTo(1);
        assertThat(requireOutbox(id).status()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void dbCommitThenFailBeforeOffsetAck_redeliveryDoesNotDuplicateBusinessState() {
        UUID tenantId = UUID.randomUUID();
        String eventId = ingestAccepted(tenantId, "offset-gap-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);
        double duplicatesBefore = counterValue("usagecore.usage.events.processed", "result", "duplicate");

        gate.failAfterProcessRemaining.set(1);
        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(gate.failAfterProcessRemaining.get()).isZero()
        );
        assertThat(countByEventId("processed_event", id)).isEqualTo(1);
        assertThat(countByEventId("usage_ledger", id)).isEqualTo(1);

        awaitLedgerAndInbox(id, 1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(aggregateValue(tenantId)).isEqualTo(1L);
            assertThat(countTableForTenant("usage_ledger", tenantId)).isEqualTo(1);
            assertThat(countTableForTenant("processed_event", tenantId)).isEqualTo(1);
            assertThat(counterValue("usagecore.usage.events.processed", "result", "duplicate"))
                    .isGreaterThan(duplicatesBefore);
        });
    }

    @Test
    void duplicateStorm_oneHundredRedeliveries_oneBusinessEffect() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String eventId = ingestAccepted(tenantId, "storm-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);
        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);
        awaitLedgerAndInbox(id, 1);

        String envelope = requireOutbox(id).serializedEnvelope();
        String partitionKey = requireOutbox(id).partitionKey();
        double duplicatesBefore = counterValue("usagecore.usage.events.processed", "result", "duplicate");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all"
        ))) {
            for (int i = 0; i < 100; i++) {
                producer.send(new ProducerRecord<>(TOPIC, partitionKey, envelope)).get(10, TimeUnit.SECONDS);
            }
        }

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countByEventId("usage_ledger", id)).isEqualTo(1);
            assertThat(countByEventId("processed_event", id)).isEqualTo(1);
            assertThat(aggregateValue(tenantId)).isEqualTo(1L);
            assertThat(countTableForTenant("usage_ledger", tenantId)).isEqualTo(1);
        });
        assertThat(counterValue("usagecore.usage.events.processed", "result", "duplicate"))
                .isGreaterThan(duplicatesBefore);
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
