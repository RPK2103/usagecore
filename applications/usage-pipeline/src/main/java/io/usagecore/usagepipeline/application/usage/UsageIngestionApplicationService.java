package io.usagecore.usagepipeline.application.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.CorrelationIdAccessor;
import io.usagecore.usagepipeline.application.security.CurrentPrincipal;
import io.usagecore.usagepipeline.application.security.UsageAccessGuard;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durably accepts usage events into PostgreSQL (ingestion + outbox) in one transaction.
 * HTTP 202 means durably accepted for asynchronous processing — not Kafka ack, aggregation,
 * quota, or billing.
 */
@Service
public class UsageIngestionApplicationService {

    public static final String ACCEPTED = "ACCEPTED";

    private final CurrentPrincipal currentPrincipal;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final UsageIngestionRepository usageIngestionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaProperties kafkaProperties;
    private final Clock clock;

    public UsageIngestionApplicationService(
            CurrentPrincipal currentPrincipal,
            CorrelationIdAccessor correlationIdAccessor,
            UsageIngestionRepository usageIngestionRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            KafkaProperties kafkaProperties,
            Clock clock
    ) {
        this.currentPrincipal = currentPrincipal;
        this.correlationIdAccessor = correlationIdAccessor;
        this.usageIngestionRepository = usageIngestionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    @Transactional
    public UsageIngestionResult ingest(
            String productKey,
            String meterKey,
            long quantity,
            Instant occurredAt,
            String idempotencyKey
    ) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        AuthenticatedPrincipal principal = currentPrincipal.require();
        UsageAccessGuard.requireUsageSubmitAuthority(principal);
        UUID tenantId = principal.tenantId().orElseThrow();

        UUID eventId = UUID.randomUUID();
        String correlationId = correlationIdAccessor.currentCorrelationId();
        Instant acceptedAt = clock.instant();
        String partitionKey = UsagePartitionKey.of(tenantId, productKey, meterKey);

        UsageIngestionRecord candidate = new UsageIngestionRecord(
                UUID.randomUUID(),
                eventId,
                tenantId,
                principal.subject(),
                productKey,
                meterKey,
                quantity,
                occurredAt,
                idempotencyKey,
                correlationId,
                acceptedAt
        );

        Optional<UUID> insertedId = usageIngestionRepository.insertIfAbsent(candidate);
        if (insertedId.isEmpty()) {
            return resolveIdempotentReplay(tenantId, idempotencyKey, productKey, meterKey, quantity, occurredAt);
        }

        EventEnvelope<UsageReceivedPayload> event = new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                occurredAt,
                tenantId,
                partitionKey,
                correlationId,
                null,
                null,
                acceptedAt,
                new UsageReceivedPayload(
                        productKey,
                        meterKey,
                        quantity,
                        idempotencyKey,
                        principal.subject()
                )
        );

        String serializedEnvelope;
        try {
            serializedEnvelope = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize UsageReceived envelope", ex);
        }

        outboxEventRepository.insertPending(new OutboxEventRecord(
                UUID.randomUUID(),
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                kafkaProperties.topics().usageReceived(),
                partitionKey,
                serializedEnvelope,
                OutboxStatus.PENDING,
                acceptedAt,
                null
        ));

        return new UsageIngestionResult(eventId, ACCEPTED, correlationId, false);
    }

    private UsageIngestionResult resolveIdempotentReplay(
            UUID tenantId,
            String idempotencyKey,
            String productKey,
            String meterKey,
            long quantity,
            Instant occurredAt
    ) {
        UsageIngestionRecord existing = usageIngestionRepository
                .findByTenantAndIdempotencyKey(tenantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency conflict detected but existing ingestion row was not found"
                ));

        if (!sameLogicalPayload(existing, productKey, meterKey, quantity, occurredAt)) {
            throw new IdempotencyConflictException(
                    "Idempotency key already used with a different usage payload"
            );
        }

        return new UsageIngestionResult(
                existing.eventId(),
                ACCEPTED,
                existing.correlationId(),
                true
        );
    }

    private static boolean sameLogicalPayload(
            UsageIngestionRecord existing,
            String productKey,
            String meterKey,
            long quantity,
            Instant occurredAt
    ) {
        return Objects.equals(existing.productKey(), productKey)
                && Objects.equals(existing.meterKey(), meterKey)
                && existing.quantity() == quantity
                && Objects.equals(existing.occurredAt(), occurredAt);
    }
}
