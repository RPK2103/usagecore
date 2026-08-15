package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.usagepipeline.adapters.observability.OutboxPublishSpanSupport;
import io.usagecore.usagepipeline.adapters.outbound.messaging.SpringKafkaUsageEventPublisher;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsageEventPublisher;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Phase 5A: Kafka unavailability must not block durable HTTP acceptance.
 * Recovery after a simulated publish failure is proven with a controllable publisher gate
 * (not a full broker stop/start — that belongs to Phase 10 ops experiments).
 */
@Import(UsageOutboxFailureIntegrationTest.ControllablePublisherConfig.class)
class UsageOutboxFailureIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private ControllablePublisherConfig.Gate gate;

    @Autowired
    private OutboxPublisherApplicationService outboxPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        gate.fail.set(true);
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
    }

    @Test
    void kafkaUnavailable_httpStillReturns202_outboxRemainsPending() {
        String eventId = givenBearer(developerToken(ACME_TENANT))
                .header("X-Correlation-Id", "kafka-down-corr")
                .body(body("export-job-174"))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .body("status", equalTo("ACCEPTED"))
                .body("eventId", notNullValue())
                .extract()
                .path("eventId");

        assertThat(countIngestions()).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
        assertThat(outboxEventRepository.findByEventId(UUID.fromString(eventId)))
                .isPresent()
                .get()
                .extracting(r -> r.status())
                .isEqualTo(OutboxStatus.PENDING);

        assertThatThrownBy(() -> outboxPublisher.publishBatch(10))
                .isInstanceOf(UsagePublicationException.class);

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(0);
    }

    @Test
    void afterPublishRecovered_publisherDeliversOriginalEventId() {
        String eventId = givenBearer(developerToken(ACME_TENANT))
                .body(body("export-job-recovery"))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .extract()
                .path("eventId");

        assertThatThrownBy(() -> outboxPublisher.publishBatch(10))
                .isInstanceOf(UsagePublicationException.class);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);

        gate.fail.set(false);
        int published = outboxPublisher.publishBatch(10);
        assertThat(published).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
        assertThat(outboxEventRepository.findByEventId(UUID.fromString(eventId)))
                .isPresent()
                .get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(OutboxStatus.PUBLISHED);
                    assertThat(row.eventId().toString()).isEqualTo(eventId);
                    assertThat(row.serializedEnvelope()).contains(eventId);
                });
    }

    private long countIngestions() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_ingestion", Long.class);
        return count == null ? 0L : count;
    }

    private static Map<String, Object> body(String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", "2026-08-12T14:30:00Z");
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    @TestConfiguration
    static class ControllablePublisherConfig {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        @Primary
        UsageEventPublisher controllablePublisher(
                Gate gate,
                KafkaTemplate<String, String> kafkaTemplate,
                KafkaProperties kafkaProperties,
                OutboxPublishSpanSupport outboxPublishSpanSupport,
                ObjectMapper objectMapper
        ) {
            UsageEventPublisher delegate = new SpringKafkaUsageEventPublisher(
                    kafkaTemplate,
                    kafkaProperties,
                    outboxPublishSpanSupport,
                    objectMapper
            );
            return (
                    topic,
                    partitionKey,
                    serializedEnvelope,
                    eventId,
                    eventType,
                    eventVersion,
                    correlationId
            ) -> {
                if (gate.fail.get()) {
                    throw new UsagePublicationException("simulated broker unavailable");
                }
                delegate.publishSerialized(
                        topic,
                        partitionKey,
                        serializedEnvelope,
                        eventId,
                        eventType,
                        eventVersion,
                        correlationId
                );
            };
        }

        static final class Gate {
            final AtomicBoolean fail = new AtomicBoolean(true);
        }
    }
}
