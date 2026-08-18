package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Flagship Kafka broker pause/unpause: HTTP 202 with PENDING outbox, then drain after recovery.
 */
class KafkaBrokerOutageIntegrationTest extends AbstractIsolatedOutageIntegrationTest {

    private static final String CONSUMER_GROUP = "usagecore-resilience-kafka-outage";
    private static final String TOPIC = "usagecore.resilience.kafka-outage.v1";
    private static final String DLQ = "usagecore.resilience.kafka-outage.v1.dlq";

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("usagecore.kafka.topics.usage-received", () -> TOPIC);
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> DLQ);
    }

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        cleanUsageTables();
    }

    @Test
    void kafkaUnavailable_httpReturns202_recoveryPublishesSameEventIdOnce() {
        assertThat(environment.getProperty("usagecore.kafka.consumer-group")).isEqualTo(CONSUMER_GROUP);
        assertThat(environment.getProperty("usagecore.kafka.consumer-group"))
                .isNotEqualTo("usagecore-usage-pipeline-v1");
        assertThat(environment.getProperty("management.health.kafka.enabled")).isEqualTo("false");

        UUID tenantId = UUID.randomUUID();
        TestcontainersPause.pause(KAFKA);

        givenActuator().get("/actuator/health/readiness")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(usageBody("unauth-" + UUID.randomUUID()))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(401);

        String eventId = ingestAccepted(tenantId, "kafka-outage-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);

        assertThat(countTableForTenant("usage_ingestion", tenantId)).isEqualTo(1);
        OutboxEventRecord pending = requireOutbox(id);
        assertThat(pending.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.eventId()).isEqualTo(id);
        assertThat(pending.serializedEnvelope()).contains(eventId);
        assertThat(countByEventId("usage_ledger", id)).isZero();
        assertThat(countByEventId("processed_event", id)).isZero();
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);

        double failuresBefore = counterValue("usagecore.outbox.publish", "result", "failure");
        assertThatThrownBy(() -> outboxPublisher.publishBatch(10))
                .isInstanceOf(UsagePublicationException.class);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isZero();
        assertThat(counterValue("usagecore.outbox.publish", "result", "failure")).isGreaterThan(failuresBefore);

        String prometheusDuringOutage = givenActuator().get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(prometheusDuringOutage).contains("usagecore_outbox_pending");
        assertThat(prometheusDuringOutage).contains("usagecore_outbox_publish_total");

        TestcontainersPause.unpause(KAFKA);
        String originalEnvelope = pending.serializedEnvelope();
        publishUntilPendingDrained(1);

        OutboxEventRecord published = requireOutbox(id);
        assertThat(published.status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.eventId()).isEqualTo(id);
        assertThat(published.serializedEnvelope()).isEqualTo(originalEnvelope);

        awaitLedgerAndInbox(id, 1);
        assertThat(aggregateValue(tenantId)).isEqualTo(1L);
        assertThat(countTableForTenant("usage_ledger", tenantId)).isEqualTo(1);
        assertThat(countTableForTenant("processed_event", tenantId)).isEqualTo(1);
    }

    @Test
    void kafkaOutageBacklog_fiftyEventsDrainWithoutDuplicateContribution() {
        UUID tenantId = UUID.randomUUID();
        int n = 50;
        TestcontainersPause.pause(KAFKA);

        List<String> eventIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            eventIds.add(ingestAccepted(tenantId, "backlog-" + i + "-" + UUID.randomUUID()));
        }

        assertThat(countTableForTenant("usage_ingestion", tenantId)).isEqualTo(n);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(n);
        assertThat(countTableForTenant("usage_ledger", tenantId)).isZero();

        String prometheus = givenActuator().get("/actuator/prometheus").then().statusCode(200).extract().asString();
        assertThat(prometheus).contains("usagecore_outbox_pending");

        TestcontainersPause.unpause(KAFKA);
        publishUntilPendingDrained(n);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(countTableForTenant("usage_ledger", tenantId)).isEqualTo(n);
            assertThat(countTableForTenant("processed_event", tenantId)).isEqualTo(n);
            assertThat(aggregateValue(tenantId)).isEqualTo(n);
        });

        for (String eventId : eventIds) {
            UUID id = UUID.fromString(eventId);
            assertThat(countByEventId("usage_ledger", id)).isEqualTo(1);
            assertThat(countByEventId("processed_event", id)).isEqualTo(1);
            assertThat(requireOutbox(id).status()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(requireOutbox(id).serializedEnvelope()).contains(eventId);
        }
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isZero();
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
