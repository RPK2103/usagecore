package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PostgreSQL pause: no false HTTP 202, readiness reflects DB, recovery without rewriting tables.
 */
class PostgreSqlOutageIntegrationTest extends AbstractIsolatedOutageIntegrationTest {

    private static final String CONSUMER_GROUP = "usagecore-resilience-postgres-outage";
    private static final String TOPIC = "usagecore.resilience.postgres-outage.v1";
    private static final String DLQ = "usagecore.resilience.postgres-outage.v1.dlq";

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("usagecore.kafka.topics.usage-received", () -> TOPIC);
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> DLQ);
    }

    @BeforeEach
    void clean() {
        RestAssured.config = RestAssuredConfig.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 5000)
                        .setParam("http.socket.timeout", 10000)
        );
        cleanUsageTables();
    }

    @Test
    void postgresUnavailable_usageIngestDoesNotReturn202_noPartialRows_thenRecovers() {
        UUID tenantId = UUID.randomUUID();
        String key = "pg-outage-" + UUID.randomUUID();
        long ingestionsBefore = countTable("usage_ingestion");
        long outboxBefore = countTable("outbox_event");

        givenActuator().get("/actuator/health/liveness").then().statusCode(200);
        givenActuator().get("/actuator/health/readiness")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        TestcontainersPause.pause(POSTGRES);

        givenActuator().get("/actuator/health/readiness")
                .then()
                .statusCode(anyOf(equalTo(503), equalTo(500)))
                .body("status", not(equalTo("UP")));

        givenActuator().get("/actuator/health/liveness").then().statusCode(200);

        int ingestStatus = givenBearer(tenantId)
                .body(usageBody(key))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(anyOf(equalTo(503), equalTo(500)))
                .extract()
                .statusCode();
        assertThat(ingestStatus).isNotEqualTo(202);

        TestcontainersPause.unpause(POSTGRES);

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            givenActuator().get("/actuator/health/readiness")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));
            long ingestions = countTable("usage_ingestion");
            long outbox = countTable("outbox_event");
            assertThat(ingestions).isEqualTo(ingestionsBefore);
            assertThat(outbox).isEqualTo(outboxBefore);
            assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(0);
        });

        String eventId = ingestAccepted(tenantId, "pg-recovered-" + UUID.randomUUID());
        UUID id = UUID.fromString(eventId);
        assertThat(countTableForTenant("usage_ingestion", tenantId)).isEqualTo(1);
        assertThat(requireOutbox(id).status()).isEqualTo(OutboxStatus.PENDING);

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);
        awaitLedgerAndInbox(id, 1);
        assertThat(aggregateValue(tenantId)).isEqualTo(1L);
    }
}
