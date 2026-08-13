package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRecord;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves inbox claim + ledger insert are one transaction: ledger failure rolls back the claim.
 * Meter resolution occurs before ledger insert (needed for late classification), so fixtures
 * must seed an ACTIVE meter for the failure path to reach ledger persistence.
 * <p>
 * Kafka listener is disabled: this context installs a failing ledger {@code @Primary} bean and
 * shares Testcontainers Postgres/Kafka with sibling classes; an active consumer would race them.
 */
@Import(IdempotentConsumerAtomicityIntegrationTest.FailingLedgerConfig.class)
class IdempotentConsumerAtomicityIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final UUID EVENT_ID = UUID.fromString("12121212-1212-1212-1212-121212121212");

    @DynamicPropertySource
    static void atomicityConsumerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-atomicity-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void ledgerInsertFailure_rollsBackProcessedEvent_noPartialState() {
        EventEnvelope<UsageReceivedPayload> event = new EventEnvelope<>(
                EVENT_ID,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                OCCURRED,
                TENANT,
                UsagePartitionKey.of(TENANT, "datapilot-cloud", "scheduled_export"),
                "corr-atomic",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "scheduled_export",
                        1L,
                        "export-job-atomic-consumer",
                        "svc"
                )
        );

        assertThatThrownBy(() -> usageReceivedProcessor.process(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated ledger persistence failure");

        Long processed = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Long.class);
        Long ledger = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_ledger", Long.class);
        assertThat(processed).isZero();
        assertThat(ledger).isZero();
    }

    @TestConfiguration
    static class FailingLedgerConfig {
        @Bean
        @Primary
        UsageLedgerRepository failingUsageLedgerRepository() {
            return new UsageLedgerRepository() {
                @Override
                public void insert(UsageLedgerRecord record) {
                    throw new IllegalStateException("simulated ledger persistence failure");
                }

                @Override
                public Optional<UsageLedgerRecord> findByEventId(UUID eventId) {
                    return Optional.empty();
                }

                @Override
                public long countByEventId(UUID eventId) {
                    return 0;
                }

                @Override
                public long countAll() {
                    return 0;
                }
            };
        }
    }
}
