package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.usagecore.usagepipeline.adapters.outbound.persistence.JdbcOutboxEventRepository;
import io.usagecore.usagepipeline.adapters.outbound.persistence.JdbcUsageIngestionRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.usage.UsageIngestionRecord;
import io.usagecore.usagepipeline.application.usage.UsageIngestionRepository;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.QuotaCommercialFixtureSeeder;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves ACCEPTED consume paths are atomic: mid-transaction failures roll back
 * quota_consumption, quota_state, usage_ingestion, and outbox_event.
 */
@Import(QuotaConsumptionAtomicityIntegrationTest.FailingPersistenceConfig.class)
class QuotaConsumptionAtomicityIntegrationTest extends AbstractUsageApiIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-13T10:00:00Z");
    private static final Instant JAN_START = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Distinct consumer group + no listener auto-startup: this context must not
     * compete for partitions or disturb other AbstractUsageApiIntegrationTest contexts
     * that share the same Testcontainers Kafka broker.
     */
    @DynamicPropertySource
    static void isolatedKafka(DynamicPropertyRegistry registry) {
        registry.add(
                "usagecore.kafka.consumer-group",
                () -> "usagecore-usage-pipeline-v1-quota-atomicity-test"
        );
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FailingPersistenceConfig.Gate gate;

    @BeforeEach
    void setUp() {
        gate.failOutbox.set(false);
        gate.failIngestion.set(false);

        jdbc.update("DELETE FROM quota_consumption");
        jdbc.update("DELETE FROM quota_state");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM usage_ingestion");
        jdbc.update("DELETE FROM entitlement");
        jdbc.update("DELETE FROM contract_version");
        jdbc.update("DELETE FROM contract");

        QuotaCommercialFixtureSeeder seeder = new QuotaCommercialFixtureSeeder(jdbc);
        seeder.ensureTenant(ACME, "acme");
        seeder.ensureCatalogue();
        seeder.seedActivatedEntitlement(
                ACME,
                "acme-dp",
                1,
                JAN_START,
                null,
                MeterDefinitionFixtureSeeder.FEATURE_API_ACCESS,
                "LIMITED",
                100L
        );
    }

    @Test
    void outboxInsertFailure_rollsBackQuotaUsageAndOutbox() {
        gate.failOutbox.set(true);

        givenBearer(developerToken(ACME))
                .body(body("atomic-outbox-fail"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(500)
                .body("errorCode", equalTo("INTERNAL_ERROR"));

        assertFullyRolledBack();
    }

    @Test
    void ingestionInsertFailure_rollsBackBeforeOutbox() {
        gate.failIngestion.set(true);

        givenBearer(developerToken(ACME))
                .body(body("atomic-ingestion-fail"))
                .when()
                .post("/usage/consume")
                .then()
                .statusCode(500)
                .body("errorCode", equalTo("INTERNAL_ERROR"));

        assertFullyRolledBack();
    }

    private void assertFullyRolledBack() {
        assertThat(count("quota_consumption")).isZero();
        assertThat(count("quota_state")).isZero();
        assertThat(count("usage_ingestion")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private static Map<String, Object> body(String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", MeterDefinitionFixtureSeeder.PRODUCT_KEY);
        body.put("meterKey", MeterDefinitionFixtureSeeder.METER_API_REQUESTS);
        body.put("quantity", 5);
        body.put("occurredAt", OCCURRED.toString());
        body.put("idempotencyKey", idempotencyKey);
        return body;
    }

    @TestConfiguration
    static class FailingPersistenceConfig {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        @Primary
        UsageIngestionRepository controllableUsageIngestionRepository(JdbcTemplate jdbcTemplate, Gate gate) {
            UsageIngestionRepository delegate = new JdbcUsageIngestionRepository(jdbcTemplate);
            return new UsageIngestionRepository() {
                @Override
                public Optional<UUID> insertIfAbsent(UsageIngestionRecord record) {
                    if (gate.failIngestion.get()) {
                        throw new IllegalStateException("simulated usage_ingestion persistence failure");
                    }
                    return delegate.insertIfAbsent(record);
                }

                @Override
                public Optional<UsageIngestionRecord> findByTenantAndIdempotencyKey(
                        UUID tenantId,
                        String idempotencyKey
                ) {
                    return delegate.findByTenantAndIdempotencyKey(tenantId, idempotencyKey);
                }

                @Override
                public long countByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
                    return delegate.countByTenantAndIdempotencyKey(tenantId, idempotencyKey);
                }
            };
        }

        @Bean
        @Primary
        OutboxEventRepository controllableOutboxEventRepository(JdbcTemplate jdbcTemplate, Gate gate) {
            OutboxEventRepository delegate = new JdbcOutboxEventRepository(jdbcTemplate);
            return new OutboxEventRepository() {
                @Override
                public void insertPending(OutboxEventRecord record) {
                    if (gate.failOutbox.get()) {
                        throw new IllegalStateException("simulated outbox persistence failure");
                    }
                    delegate.insertPending(record);
                }

                @Override
                public List<OutboxEventRecord> claimPending(int batchSize) {
                    return delegate.claimPending(batchSize);
                }

                @Override
                public void markPublished(UUID id, Instant publishedAt) {
                    delegate.markPublished(id, publishedAt);
                }

                @Override
                public Optional<OutboxEventRecord> findByEventId(UUID eventId) {
                    return delegate.findByEventId(eventId);
                }

                @Override
                public long countByStatus(OutboxStatus status) {
                    return delegate.countByStatus(status);
                }

                @Override
                public long countAll() {
                    return delegate.countAll();
                }
            };
        }

        static final class Gate {
            final AtomicBoolean failOutbox = new AtomicBoolean(false);
            final AtomicBoolean failIngestion = new AtomicBoolean(false);
        }
    }
}
