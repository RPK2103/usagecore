package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.application.usage.UsageWindow;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import java.time.Instant;
import java.util.List;
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
 * Window-aggregate failure after inbox/ledger/lifetime work begins rolls back all effects.
 */
@Import(UsageAggregationAtomicityIntegrationTest.FailingWindowAggregateConfig.class)
class UsageAggregationAtomicityIntegrationTest extends AbstractIdempotentConsumerIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @DynamicPropertySource
    static void atomicityProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-agg-atomicity-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAndSeed() {
        jdbcTemplate.update("DELETE FROM usage_window_aggregate");
        jdbcTemplate.update("DELETE FROM usage_aggregate");
        jdbcTemplate.update("DELETE FROM usage_ledger");
        jdbcTemplate.update("DELETE FROM processed_event");
        new MeterDefinitionFixtureSeeder(jdbcTemplate).ensureDataPilotProductAndMeters();
    }

    @Test
    void aggregateFailure_rollsBackInboxAndLedger() {
        EventEnvelope<UsageReceivedPayload> event = new EventEnvelope<>(
                UUID.fromString("cccccccc-dddd-eeee-ffff-000000000001"),
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                Instant.parse("2026-08-12T10:00:00Z"),
                ACME,
                UsagePartitionKey.of(ACME, "datapilot-cloud", "api_requests"),
                "corr-agg-atomic",
                null,
                null,
                Instant.parse("2026-08-12T14:31:00Z"),
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "api_requests",
                        10L,
                        "agg-atomic-fail",
                        "svc"
                )
        );

        assertThatThrownBy(() -> usageReceivedProcessor.process(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated window aggregate persistence failure");

        Long processed = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM processed_event", Long.class);
        Long ledger = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_ledger", Long.class);
        Long aggregates = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_aggregate", Long.class);
        Long windowAggregates =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_window_aggregate", Long.class);
        assertThat(processed).isZero();
        assertThat(ledger).isZero();
        assertThat(aggregates).isZero();
        assertThat(windowAggregates).isZero();
    }

    @TestConfiguration
    static class FailingWindowAggregateConfig {
        @Bean
        @Primary
        UsageWindowAggregateRepository failingUsageWindowAggregateRepository() {
            return new UsageWindowAggregateRepository() {
                @Override
                public void applyEvent(
                        UUID tenantId,
                        ActiveMeterDefinition meter,
                        UsageWindow window,
                        long quantity,
                        Instant occurredAt,
                        Instant updatedAt
                ) {
                    throw new IllegalStateException("simulated window aggregate persistence failure");
                }

                @Override
                public Optional<UsageWindowAggregateRecord> findByTenantMeterAndWindow(
                        UUID tenantId,
                        UUID meterDefinitionId,
                        Instant windowStart,
                        Instant windowEnd
                ) {
                    return Optional.empty();
                }

                @Override
                public Optional<UsageWindowAggregateRecord> findByTenantProductMeterAndWindow(
                        UUID tenantId,
                        String productKey,
                        String meterKey,
                        Instant windowStart,
                        Instant windowEnd
                ) {
                    return Optional.empty();
                }

                @Override
                public List<UsageWindowAggregateRecord> findByTenantProductMeterOverlapping(
                        UUID tenantId,
                        String productKey,
                        String meterKey,
                        Instant fromInclusive,
                        Instant toExclusive
                ) {
                    return List.of();
                }

                @Override
                public long countAll() {
                    return 0;
                }
            };
        }
    }
}
