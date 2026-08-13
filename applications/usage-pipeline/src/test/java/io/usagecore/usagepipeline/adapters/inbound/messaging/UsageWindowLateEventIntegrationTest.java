package io.usagecore.usagepipeline.adapters.inbound.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
import io.usagecore.usagepipeline.support.MeterDefinitionFixtureSeeder;
import io.usagecore.usagepipeline.support.TestSecurityConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Late-event classification with injected September processing clock.
 * August event-time usage must update the August window and be marked late.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
        TestSecurityConfiguration.class,
        UsageWindowLateEventIntegrationTest.SeptemberClockConfig.class
})
class UsageWindowLateEventIntegrationTest {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant PROCESSING = Instant.parse("2026-09-03T12:00:00Z");
    private static final Instant AUG_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant SEP_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OCT_START = Instant.parse("2026-10-01T00:00:00Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
        registry.add("usagecore.kafka.topics.usage-received", () -> "usagecore.usage.received.v1");
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> "usagecore.usage.received.v1.dlq");
        registry.add("usagecore.outbox.publisher.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-late-event-test");
    }

    @Autowired
    private UsageReceivedProcessor usageReceivedProcessor;

    @Autowired
    private UsageWindowAggregateRepository usageWindowAggregateRepository;

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
    void lateAugustEvent_accepted_updatesAugustNotSeptember_andMarkedLate() {
        UUID eventId = UUID.fromString("eeeeeeee-ffff-aaaa-bbbb-cccccccccccc");
        Instant occurredAt = Instant.parse("2026-08-28T10:00:00Z");

        usageReceivedProcessor.process(new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                occurredAt,
                ACME,
                UsagePartitionKey.of(ACME, "datapilot-cloud", "api_requests"),
                "corr-late",
                null,
                null,
                PROCESSING,
                new UsageReceivedPayload(
                        "datapilot-cloud",
                        "api_requests",
                        42L,
                        "late-aug-event",
                        "svc-datapilot"
                )
        ));

        UsageWindowAggregateRecord august = usageWindowAggregateRepository
                .findByTenantProductMeterAndWindow(ACME, "datapilot-cloud", "api_requests", AUG_START, SEP_START)
                .orElseThrow();
        assertThat(august.aggregateValue()).isEqualTo(42L);
        assertThat(august.eventCount()).isEqualTo(1L);

        assertThat(usageWindowAggregateRepository
                .findByTenantProductMeterAndWindow(ACME, "datapilot-cloud", "api_requests", SEP_START, OCT_START))
                .isEmpty();

        Boolean late = jdbcTemplate.queryForObject(
                "SELECT is_late FROM usage_ledger WHERE event_id = ?",
                Boolean.class,
                eventId
        );
        assertThat(late).isTrue();

        java.sql.Timestamp recordedAt = jdbcTemplate.queryForObject(
                "SELECT recorded_at FROM usage_ledger WHERE event_id = ?",
                java.sql.Timestamp.class,
                eventId
        );
        assertThat(recordedAt.toInstant()).isEqualTo(PROCESSING);
    }

    @TestConfiguration
    static class SeptemberClockConfig {
        @Bean
        @Primary
        Clock septemberProcessingClock() {
            return Clock.fixed(PROCESSING, ZoneOffset.UTC);
        }
    }
}
