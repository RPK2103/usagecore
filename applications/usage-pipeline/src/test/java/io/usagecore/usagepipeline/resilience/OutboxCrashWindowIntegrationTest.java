package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Outbox crash windows: fail before send, and Kafka ACK then fail before PUBLISHED.
 */
@Import(OutboxCrashWindowTestConfiguration.class)
class OutboxCrashWindowIntegrationTest extends AbstractResilienceIntegrationTest {

    private static final String CONSUMER_GROUP = "usagecore-resilience-outbox-crash";
    private static final String TOPIC = "usagecore.resilience.outbox-crash.v1";
    private static final String DLQ = "usagecore.resilience.outbox-crash.v1.dlq";

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("usagecore.kafka.topics.usage-received", () -> TOPIC);
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> DLQ);
    }

    @Autowired
    private OutboxCrashWindowTestConfiguration.Gate gate;

    @BeforeEach
    void clean() {
        gate.mode.set(OutboxCrashWindowTestConfiguration.Mode.PASS);
        cleanUsageTables();
    }

    @Test
    void failBeforeKafkaSend_rowStaysPending_thenPublishesOriginalEnvelope() {
        UUID tenantId = UUID.randomUUID();
        String eventId = ingestAccepted(tenantId, "pre-send-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);
        OutboxEventRecord before = requireOutbox(id);
        String envelope = before.serializedEnvelope();

        gate.mode.set(OutboxCrashWindowTestConfiguration.Mode.FAIL_BEFORE_SEND);
        assertThatThrownBy(() -> outboxPublisher.publishBatch(10))
                .isInstanceOf(UsagePublicationException.class)
                .hasMessageContaining("before Kafka send");

        OutboxEventRecord stillPending = requireOutbox(id);
        assertThat(stillPending.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stillPending.serializedEnvelope()).isEqualTo(envelope);
        assertThat(countByEventId("usage_ledger", id)).isZero();

        gate.mode.set(OutboxCrashWindowTestConfiguration.Mode.PASS);
        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        OutboxEventRecord published = requireOutbox(id);
        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.eventId()).isEqualTo(id);
        assertThat(published.serializedEnvelope()).isEqualTo(envelope);

        awaitLedgerAndInbox(id, 1);
        assertThat(aggregateValue(tenantId)).isEqualTo(1L);
    }

    @Test
    void kafkaAckThenCrashBeforePublished_retryReusesEventId_consumerAppliesOnce() {
        UUID tenantId = UUID.randomUUID();
        String eventId = ingestAccepted(tenantId, "ack-crash-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);
        OutboxEventRecord original = requireOutbox(id);
        String envelope = original.serializedEnvelope();

        gate.mode.set(OutboxCrashWindowTestConfiguration.Mode.FAIL_AFTER_ACK);
        assertThatThrownBy(() -> outboxPublisher.publishBatch(10))
                .isInstanceOf(UsagePublicationException.class)
                .hasMessageContaining("after Kafka ACK");

        OutboxEventRecord afterCrash = requireOutbox(id);
        assertThat(afterCrash.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterCrash.serializedEnvelope()).isEqualTo(envelope);
        assertThat(afterCrash.eventId()).isEqualTo(id);

        int copiesAfterFirstSend = countTopicRecordsContaining(TOPIC, eventId, 1, Duration.ofSeconds(20));
        assertThat(copiesAfterFirstSend).isGreaterThanOrEqualTo(1);

        gate.mode.set(OutboxCrashWindowTestConfiguration.Mode.PASS);
        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        OutboxEventRecord published = requireOutbox(id);
        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.serializedEnvelope()).isEqualTo(envelope);
        assertThat(published.eventId().toString()).isEqualTo(eventId);

        int copiesAfterRetry = countTopicRecordsContaining(TOPIC, eventId, 2, Duration.ofSeconds(20));
        assertThat(copiesAfterRetry).isGreaterThanOrEqualTo(2);

        awaitLedgerAndInbox(id, 1);
        assertThat(aggregateValue(tenantId)).isEqualTo(1L);
        assertThat(countTableForTenant("usage_ledger", tenantId)).isEqualTo(1);
        assertThat(countTableForTenant("processed_event", tenantId)).isEqualTo(1);
    }
}
