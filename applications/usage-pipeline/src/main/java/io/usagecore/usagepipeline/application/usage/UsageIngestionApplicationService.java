package io.usagecore.usagepipeline.application.usage;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.CorrelationIdAccessor;
import io.usagecore.usagepipeline.application.security.CurrentPrincipal;
import io.usagecore.usagepipeline.application.security.UsageAccessGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Accepts authenticated usage events and publishes them to Kafka.
 * HTTP 202 means Kafka acknowledged publication — not aggregation, quota, or billing.
 */
@Service
public class UsageIngestionApplicationService {

    public static final String ACCEPTED = "ACCEPTED";

    private final CurrentPrincipal currentPrincipal;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final UsageEventPublisher usageEventPublisher;
    private final Clock clock;

    public UsageIngestionApplicationService(
            CurrentPrincipal currentPrincipal,
            CorrelationIdAccessor correlationIdAccessor,
            UsageEventPublisher usageEventPublisher,
            Clock clock
    ) {
        this.currentPrincipal = currentPrincipal;
        this.correlationIdAccessor = correlationIdAccessor;
        this.usageEventPublisher = usageEventPublisher;
        this.clock = clock;
    }

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
        Instant publishedAt = clock.instant();
        String partitionKey = UsagePartitionKey.of(tenantId, productKey, meterKey);
        String aggregateId = partitionKey;

        EventEnvelope<UsageReceivedPayload> event = new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                occurredAt,
                tenantId,
                aggregateId,
                correlationId,
                null,
                null,
                publishedAt,
                new UsageReceivedPayload(
                        productKey,
                        meterKey,
                        quantity,
                        idempotencyKey,
                        principal.subject()
                )
        );

        usageEventPublisher.publish(event, partitionKey);

        return new UsageIngestionResult(eventId, ACCEPTED, correlationId);
    }
}
