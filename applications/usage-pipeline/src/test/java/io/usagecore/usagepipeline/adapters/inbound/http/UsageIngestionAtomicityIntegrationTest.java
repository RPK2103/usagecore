package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves ingestion + outbox are atomic: outbox failure rolls back the ingestion row.
 */
@Import(UsageIngestionAtomicityIntegrationTest.FailingOutboxConfig.class)
class UsageIngestionAtomicityIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM usage_ingestion");
    }

    @Test
    void outboxInsertFailure_rollsBackIngestion_noPartialState() {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", "2026-08-12T14:30:00Z");
        body.put("idempotencyKey", "export-job-atomic");

        givenBearer(developerToken(ACME_TENANT))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(500)
                .body("errorCode", equalTo("INTERNAL_ERROR"));

        Long ingestions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_ingestion", Long.class);
        Long outbox = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class);
        assertThat(ingestions).isZero();
        assertThat(outbox).isZero();
    }

    @TestConfiguration
    static class FailingOutboxConfig {
        @Bean
        @Primary
        OutboxEventRepository failingOutboxEventRepository() {
            return new OutboxEventRepository() {
                @Override
                public void insertPending(OutboxEventRecord record) {
                    throw new IllegalStateException("simulated outbox persistence failure");
                }

                @Override
                public List<OutboxEventRecord> claimPending(int batchSize) {
                    return List.of();
                }

                @Override
                public void markPublished(UUID id, Instant publishedAt) {
                }

                @Override
                public Optional<OutboxEventRecord> findByEventId(UUID eventId) {
                    return Optional.empty();
                }

                @Override
                public long countByStatus(OutboxStatus status) {
                    return 0;
                }

                @Override
                public long countAll() {
                    return 0;
                }

                @Override
                public Optional<Instant> oldestPendingCreatedAt() {
                    return Optional.empty();
                }
            };
        }
    }
}
