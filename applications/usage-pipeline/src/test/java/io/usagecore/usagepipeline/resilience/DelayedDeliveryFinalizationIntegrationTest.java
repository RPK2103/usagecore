package io.usagecore.usagepipeline.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.support.CommercialPeriodFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Durable accept while OPEN, delay Kafka, finalize period, then deliver.
 * Ledger + commercial exception; finalized aggregates are not mutated.
 */
class DelayedDeliveryFinalizationIntegrationTest extends AbstractResilienceIntegrationTest {

    private static final String CONSUMER_GROUP = "usagecore-resilience-delayed-finalization";
    private static final String TOPIC = "usagecore.resilience.delayed-finalization.v1";
    private static final String DLQ = "usagecore.resilience.delayed-finalization.v1.dlq";
    private static final Instant PERIOD_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-15T12:00:00Z");

    @DynamicPropertySource
    static void isolation(DynamicPropertyRegistry registry) {
        registry.add("usagecore.kafka.consumer-group", () -> CONSUMER_GROUP);
        registry.add("usagecore.kafka.topics.usage-received", () -> TOPIC);
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> DLQ);
    }

    @BeforeEach
    void clean() {
        new CommercialPeriodFixtureSeeder(jdbcTemplate).clearCommercialTables();
        cleanUsageTables();
    }

    @Test
    void acceptedWhileOpen_deliveredAfterFinalized_quarantinesWithoutMutatingAggregate() {
        UUID tenantId = UUID.randomUUID();
        QuotaCommercialFixtureSeeder commercial = new QuotaCommercialFixtureSeeder(jdbcTemplate);
        commercial.ensureTenant(tenantId, "resilience-delayed-" + tenantId);
        UUID productId = commercial.ensureCatalogue();
        CommercialPeriodFixtureSeeder periods = new CommercialPeriodFixtureSeeder(jdbcTemplate);
        UUID periodId = periods.insertPeriod(tenantId, productId, PERIOD_START, PERIOD_END, "OPEN");

        String eventId = givenBearer(tenantId)
                .body(usageBody("delayed-final-" + UUID.randomUUID(), OCCURRED, 1L))
                .when()
                .post("/usage/events")
                .then()
                .statusCode(202)
                .extract()
                .path("eventId");
        UUID id = UUID.fromString(eventId);

        assertThat(requireOutbox(id).status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(countByEventId("usage_ledger", id)).isZero();
        assertThat(aggregateValue(tenantId)).isZero();

        periods.forceStatus(periodId, "FINALIZED");
        assertThat(periods.periodStatus(periodId)).isEqualTo("FINALIZED");

        assertThat(outboxPublisher.publishBatch(10)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(countByEventId("processed_event", id)).isEqualTo(1);
            assertThat(countByEventId("usage_ledger", id)).isEqualTo(1);
            assertThat(periods.exceptionCountForEvent(id)).isEqualTo(1);
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM commercial_usage_exception WHERE event_id = ?",
                String.class,
                id
        )).isEqualTo("PERIOD_FINALIZED");
        assertThat(aggregateValue(tenantId)).isZero();
        Long windowRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_window_aggregate WHERE tenant_id = ?",
                Long.class,
                tenantId
        );
        assertThat(windowRows).isZero();
        assertThat(requireOutbox(id).status()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(requireOutbox(id).serializedEnvelope()).contains(eventId);
    }
}
