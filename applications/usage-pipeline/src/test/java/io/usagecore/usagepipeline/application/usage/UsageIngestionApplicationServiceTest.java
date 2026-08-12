package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.security.PlatformRole;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class UsageIngestionApplicationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED = Instant.parse("2026-08-12T14:30:00Z");
    private static final Instant FIXED = Instant.parse("2026-08-12T14:30:05Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KafkaProperties kafkaProperties = new KafkaProperties(
            new KafkaProperties.Topics("usagecore.usage.received.v1"),
            "usagecore-usage-pipeline-v1",
            java.time.Duration.ofSeconds(10)
    );

    @Test
    void ingest_persistsIngestionAndOutboxFromJwtTenantOnly() {
        InMemoryUsageIngestionRepository ingestionRepo = new InMemoryUsageIngestionRepository();
        InMemoryOutboxEventRepository outboxRepo = new InMemoryOutboxEventRepository();
        UsageIngestionApplicationService service = newService(ingestionRepo, outboxRepo);

        UsageIngestionResult result = service.ingest(
                "datapilot-cloud",
                "scheduled_export",
                1L,
                OCCURRED,
                "export-job-174"
        );

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.correlationId()).isEqualTo("corr-demo");
        assertThat(ingestionRepo.rows).hasSize(1);
        assertThat(outboxRepo.rows).hasSize(1);
        UsageIngestionRecord stored = ingestionRepo.rows.getFirst();
        assertThat(stored.tenantId()).isEqualTo(TENANT);
        assertThat(stored.eventId()).isEqualTo(result.eventId());
        OutboxEventRecord outbox = outboxRepo.rows.getFirst();
        assertThat(outbox.eventId()).isEqualTo(result.eventId());
        assertThat(outbox.eventType()).isEqualTo(EventTypes.USAGE_RECEIVED);
        assertThat(outbox.eventVersion()).isEqualTo(EventVersions.V1);
        assertThat(outbox.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.partitionKey()).isEqualTo(
                UsagePartitionKey.of(TENANT, "datapilot-cloud", "scheduled_export")
        );
        assertThat(outbox.serializedEnvelope()).contains(result.eventId().toString());
    }

    @Test
    void ingest_samePayload_idempotentReplay() {
        InMemoryUsageIngestionRepository ingestionRepo = new InMemoryUsageIngestionRepository();
        InMemoryOutboxEventRepository outboxRepo = new InMemoryOutboxEventRepository();
        UsageIngestionApplicationService service = newService(ingestionRepo, outboxRepo);

        UsageIngestionResult first = service.ingest(
                "datapilot-cloud", "scheduled_export", 1L, OCCURRED, "export-job-174"
        );
        UsageIngestionResult second = service.ingest(
                "datapilot-cloud", "scheduled_export", 1L, OCCURRED, "export-job-174"
        );

        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.idempotentReplay()).isTrue();
        assertThat(ingestionRepo.rows).hasSize(1);
        assertThat(outboxRepo.rows).hasSize(1);
    }

    @Test
    void ingest_sameKeyDifferentPayload_conflicts() {
        InMemoryUsageIngestionRepository ingestionRepo = new InMemoryUsageIngestionRepository();
        InMemoryOutboxEventRepository outboxRepo = new InMemoryOutboxEventRepository();
        UsageIngestionApplicationService service = newService(ingestionRepo, outboxRepo);

        service.ingest("datapilot-cloud", "scheduled_export", 1L, OCCURRED, "export-job-174");

        assertThatThrownBy(() -> service.ingest(
                "datapilot-cloud", "scheduled_export", 2L, OCCURRED, "export-job-174"
        )).isInstanceOf(IdempotencyConflictException.class);
        assertThat(outboxRepo.rows).hasSize(1);
    }

    @Test
    void ingest_withoutTenant_denied() {
        UsageIngestionApplicationService service = new UsageIngestionApplicationService(
                () -> new AuthenticatedPrincipal(
                        "platform-admin",
                        Optional.empty(),
                        EnumSet.of(PlatformRole.PLATFORM_ADMIN)
                ),
                () -> "corr",
                new InMemoryUsageIngestionRepository(),
                new InMemoryOutboxEventRepository(),
                objectMapper,
                kafkaProperties,
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> service.ingest(
                "datapilot-cloud",
                "scheduled_export",
                1L,
                OCCURRED,
                "export-job-174"
        )).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void unsupportedEventVersion_failsExplicitly() {
        var event = new io.usagecore.events.EventEnvelope<>(
                UUID.randomUUID(),
                EventTypes.USAGE_RECEIVED,
                "99",
                OCCURRED,
                TENANT,
                "agg",
                "corr",
                null,
                null,
                FIXED,
                new io.usagecore.events.usage.UsageReceivedPayload(
                        "datapilot-cloud", "scheduled_export", 1L, "k", "sub"
                )
        );

        assertThatThrownBy(() -> LoggingUsageReceivedProcessor.validateSupportedContract(event))
                .isInstanceOf(UnsupportedUsageEventException.class)
                .hasMessageContaining("Unsupported eventVersion");
    }

    private UsageIngestionApplicationService newService(
            InMemoryUsageIngestionRepository ingestionRepo,
            InMemoryOutboxEventRepository outboxRepo
    ) {
        return new UsageIngestionApplicationService(
                () -> new AuthenticatedPrincipal(
                        "acme-developer",
                        Optional.of(TENANT),
                        EnumSet.of(PlatformRole.DEVELOPER)
                ),
                () -> "corr-demo",
                ingestionRepo,
                outboxRepo,
                objectMapper,
                kafkaProperties,
                Clock.fixed(FIXED, ZoneOffset.UTC)
        );
    }

    private static final class InMemoryUsageIngestionRepository implements UsageIngestionRepository {
        private final List<UsageIngestionRecord> rows = new ArrayList<>();
        private final ConcurrentHashMap<String, UsageIngestionRecord> byTenantKey = new ConcurrentHashMap<>();

        @Override
        public Optional<UUID> insertIfAbsent(UsageIngestionRecord record) {
            String key = record.tenantId() + "|" + record.idempotencyKey();
            UsageIngestionRecord previous = byTenantKey.putIfAbsent(key, record);
            if (previous != null) {
                return Optional.empty();
            }
            rows.add(record);
            return Optional.of(record.id());
        }

        @Override
        public Optional<UsageIngestionRecord> findByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
            return Optional.ofNullable(byTenantKey.get(tenantId + "|" + idempotencyKey));
        }

        @Override
        public long countByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
            return findByTenantAndIdempotencyKey(tenantId, idempotencyKey).isPresent() ? 1L : 0L;
        }
    }

    private static final class InMemoryOutboxEventRepository implements OutboxEventRepository {
        private final List<OutboxEventRecord> rows = new ArrayList<>();

        @Override
        public void insertPending(OutboxEventRecord record) {
            rows.add(record);
        }

        @Override
        public List<OutboxEventRecord> claimPending(int batchSize) {
            return rows.stream()
                    .filter(r -> r.status() == OutboxStatus.PENDING)
                    .limit(batchSize)
                    .toList();
        }

        @Override
        public void markPublished(UUID id, Instant publishedAt) {
        }

        @Override
        public Optional<OutboxEventRecord> findByEventId(UUID eventId) {
            return rows.stream().filter(r -> r.eventId().equals(eventId)).findFirst();
        }

        @Override
        public long countByStatus(OutboxStatus status) {
            return rows.stream().filter(r -> r.status() == status).count();
        }

        @Override
        public long countAll() {
            return rows.size();
        }
    }
}
