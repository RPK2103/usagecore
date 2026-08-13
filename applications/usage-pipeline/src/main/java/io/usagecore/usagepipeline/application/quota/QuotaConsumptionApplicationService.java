package io.usagecore.usagepipeline.application.quota;

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
import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.IdempotencyConflictException;
import io.usagecore.usagepipeline.application.usage.MeterDefinitionLookup;
import io.usagecore.usagepipeline.application.usage.UsageIngestionRecord;
import io.usagecore.usagepipeline.application.usage.UsageIngestionRepository;
import io.usagecore.usagepipeline.application.usage.UsagePartitionKey;
import io.usagecore.usagepipeline.application.usage.UsageWindow;
import io.usagecore.usagepipeline.application.usage.UsageWindowResolver;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronous contract-aware quota admission.
 * <p>
 * Separates read-only entitlement checks from mutating quota consumption.
 * Authoritative consumption uses PostgreSQL conditional updates on {@code quota_state};
 * accepted decisions also durably enqueue usage metering via the transactional outbox
 * in the same transaction (no dual-write hole).
 * <p>
 * Reporting aggregates ({@code usage_window_aggregate}) may lag due to Kafka — they never
 * authorize overshoot.
 */
@Service
public class QuotaConsumptionApplicationService {

    private final CurrentPrincipal currentPrincipal;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final MeterDefinitionLookup meterDefinitionLookup;
    private final CommercialEntitlementLookup commercialEntitlementLookup;
    private final UsageWindowResolver usageWindowResolver;
    private final QuotaStateRepository quotaStateRepository;
    private final QuotaConsumptionRepository quotaConsumptionRepository;
    private final UsageIngestionRepository usageIngestionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaProperties kafkaProperties;
    private final Clock clock;

    public QuotaConsumptionApplicationService(
            CurrentPrincipal currentPrincipal,
            CorrelationIdAccessor correlationIdAccessor,
            MeterDefinitionLookup meterDefinitionLookup,
            CommercialEntitlementLookup commercialEntitlementLookup,
            UsageWindowResolver usageWindowResolver,
            QuotaStateRepository quotaStateRepository,
            QuotaConsumptionRepository quotaConsumptionRepository,
            UsageIngestionRepository usageIngestionRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            KafkaProperties kafkaProperties,
            Clock clock
    ) {
        this.currentPrincipal = currentPrincipal;
        this.correlationIdAccessor = correlationIdAccessor;
        this.meterDefinitionLookup = meterDefinitionLookup;
        this.commercialEntitlementLookup = commercialEntitlementLookup;
        this.usageWindowResolver = usageWindowResolver;
        this.quotaStateRepository = quotaStateRepository;
        this.quotaConsumptionRepository = quotaConsumptionRepository;
        this.usageIngestionRepository = usageIngestionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
        this.clock = clock;
    }

    @Transactional
    public QuotaConsumeResult consume(
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
        String correlationId = correlationIdAccessor.currentCorrelationId();
        Instant decidedAt = clock.instant();

        // Serialize identical (tenant, idempotencyKey) before any quota_state mutation.
        quotaConsumptionRepository.acquireIdempotencyLock(tenantId, idempotencyKey);

        Optional<QuotaConsumptionRecord> existing =
                quotaConsumptionRepository.findByTenantAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), productKey, meterKey, quantity, occurredAt);
        }

        Optional<ActiveMeterDefinition> meterOpt =
                meterDefinitionLookup.findActiveByProductKeyAndMeterKey(productKey, meterKey);
        if (meterOpt.isEmpty()) {
            QuotaConsumptionRecord unknown = buildRejectedWithoutMeter(
                    tenantId,
                    principal.subject(),
                    productKey,
                    meterKey,
                    quantity,
                    occurredAt,
                    idempotencyKey,
                    correlationId,
                    decidedAt
            );
            quotaConsumptionRepository.insertIfAbsent(unknown);
            return toResult(unknown, false);
        }

        ActiveMeterDefinition meter = meterOpt.get();
        if (meter.featureId() == null
                || meter.featureKey() == null
                || meter.featureKey().isBlank()) {
            QuotaConsumptionRecord unbound = buildRejectedUnboundMeter(
                    tenantId,
                    principal.subject(),
                    productKey,
                    meter,
                    quantity,
                    occurredAt,
                    idempotencyKey,
                    correlationId,
                    decidedAt
            );
            quotaConsumptionRepository.insertIfAbsent(unbound);
            return toResult(unbound, false);
        }

        UsageWindow window = usageWindowResolver.resolve(occurredAt, meter.aggregationWindow());

        List<CommercialEntitlementMatch> matches = commercialEntitlementLookup.findEffectiveEntitlements(
                tenantId,
                productKey,
                meter.featureKey(),
                occurredAt
        );
        if (matches.size() > 1) {
            throw new CommercialInvariantException(
                    "Multiple effective activated contract versions matched for the same commercial query"
            );
        }

        DecisionPlan plan = matches.isEmpty()
                ? rejectNoActive()
                : planFromEntitlement(matches.getFirst(), meter, window, quantity, tenantId, decidedAt);

        QuotaConsumptionRecord candidate = toRecord(
                plan,
                tenantId,
                principal.subject(),
                productKey,
                meter,
                quantity,
                occurredAt,
                window,
                idempotencyKey,
                correlationId,
                decidedAt
        );

        Optional<UUID> inserted = quotaConsumptionRepository.insertIfAbsent(candidate);
        if (inserted.isEmpty()) {
            // Should not happen after advisory lock + empty pre-check, but remain deterministic.
            QuotaConsumptionRecord winner = quotaConsumptionRepository
                    .findByTenantAndIdempotencyKey(tenantId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency conflict detected but existing quota consumption was not found"
                    ));
            return replayOrConflict(winner, productKey, meterKey, quantity, occurredAt);
        }

        if (plan.decision() == QuotaDecision.ACCEPTED) {
            persistUsageAndOutbox(candidate, principal.subject());
        }

        return toResult(candidate, false);
    }

    private DecisionPlan planFromEntitlement(
            CommercialEntitlementMatch match,
            ActiveMeterDefinition meter,
            UsageWindow window,
            long quantity,
            UUID tenantId,
            Instant decidedAt
    ) {
        return switch (match.entitlementMode()) {
            case DISABLED -> new DecisionPlan(
                    QuotaDecision.REJECTED,
                    QuotaReasonCodes.ENTITLEMENT_DISABLED,
                    0L,
                    null,
                    null,
                    null,
                    match.contractVersionId(),
                    match.contractVersionNumber()
            );
            case ENABLED -> new DecisionPlan(
                    QuotaDecision.ACCEPTED,
                    QuotaReasonCodes.ENTITLEMENT_ENABLED,
                    contributionFor(meter.aggregationType(), quantity),
                    null,
                    null,
                    null,
                    match.contractVersionId(),
                    match.contractVersionNumber()
            );
            case LIMITED -> planLimited(match, meter, window, quantity, tenantId, decidedAt);
        };
    }

    private DecisionPlan planLimited(
            CommercialEntitlementMatch match,
            ActiveMeterDefinition meter,
            UsageWindow window,
            long quantity,
            UUID tenantId,
            Instant decidedAt
    ) {
        Long configuredLimit = match.configuredLimit();
        if (configuredLimit == null || configuredLimit <= 0) {
            throw new CommercialInvariantException(
                    "LIMITED entitlement is missing a positive configuredLimit"
            );
        }
        if (meter.aggregationType() == AggregationType.MAX) {
            return new DecisionPlan(
                    QuotaDecision.REJECTED,
                    QuotaReasonCodes.UNSUPPORTED_QUOTA_METER_TYPE,
                    0L,
                    configuredLimit,
                    null,
                    null,
                    match.contractVersionId(),
                    match.contractVersionNumber()
            );
        }

        long contribution = contributionFor(meter.aggregationType(), quantity);
        if (contribution > configuredLimit) {
            long consumed = quotaStateRepository
                    .findConsumed(tenantId, meter.meterDefinitionId(), window.start(), window.end())
                    .orElse(0L);
            return new DecisionPlan(
                    QuotaDecision.REJECTED,
                    QuotaReasonCodes.REQUEST_EXCEEDS_LIMIT,
                    0L,
                    configuredLimit,
                    consumed,
                    Math.max(0L, configuredLimit - consumed),
                    match.contractVersionId(),
                    match.contractVersionNumber()
            );
        }

        Optional<Long> newConsumed = quotaStateRepository.tryConsume(
                tenantId,
                meter.meterDefinitionId(),
                window.start(),
                window.end(),
                configuredLimit,
                contribution,
                decidedAt
        );
        if (newConsumed.isPresent()) {
            long consumed = newConsumed.get();
            return new DecisionPlan(
                    QuotaDecision.ACCEPTED,
                    QuotaReasonCodes.WITHIN_QUOTA,
                    contribution,
                    configuredLimit,
                    consumed,
                    configuredLimit - consumed,
                    match.contractVersionId(),
                    match.contractVersionNumber()
            );
        }

        long consumed = quotaStateRepository
                .findConsumed(tenantId, meter.meterDefinitionId(), window.start(), window.end())
                .orElse(0L);
        return new DecisionPlan(
                QuotaDecision.REJECTED,
                QuotaReasonCodes.QUOTA_EXHAUSTED,
                0L,
                configuredLimit,
                consumed,
                Math.max(0L, configuredLimit - consumed),
                match.contractVersionId(),
                match.contractVersionNumber()
        );
    }

    private static DecisionPlan rejectNoActive() {
        return new DecisionPlan(
                QuotaDecision.REJECTED,
                QuotaReasonCodes.NO_ACTIVE_ENTITLEMENT,
                0L,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static long contributionFor(AggregationType aggregationType, long quantity) {
        return switch (aggregationType) {
            case SUM -> quantity;
            case COUNT -> 1L;
            case MAX -> quantity; // never used for LIMITED enforcement in Phase 6C
        };
    }

    private void persistUsageAndOutbox(QuotaConsumptionRecord accepted, String principalId) {
        UUID eventId = Objects.requireNonNull(accepted.eventId(), "accepted consumption requires eventId");
        String partitionKey = UsagePartitionKey.of(
                accepted.tenantId(),
                accepted.productKey(),
                accepted.meterKey()
        );

        UsageIngestionRecord ingestion = new UsageIngestionRecord(
                UUID.randomUUID(),
                eventId,
                accepted.tenantId(),
                principalId,
                accepted.productKey(),
                accepted.meterKey(),
                accepted.quantity(),
                accepted.occurredAt(),
                accepted.idempotencyKey(),
                accepted.correlationId(),
                accepted.decidedAt()
        );
        Optional<UUID> ingestionInserted = usageIngestionRepository.insertIfAbsent(ingestion);
        if (ingestionInserted.isEmpty()) {
            throw new IdempotencyConflictException(
                    "Idempotency key already used with a different usage payload"
            );
        }

        EventEnvelope<UsageReceivedPayload> event = new EventEnvelope<>(
                eventId,
                EventTypes.USAGE_RECEIVED,
                EventVersions.V1,
                accepted.occurredAt(),
                accepted.tenantId(),
                partitionKey,
                accepted.correlationId(),
                null,
                null,
                accepted.decidedAt(),
                new UsageReceivedPayload(
                        accepted.productKey(),
                        accepted.meterKey(),
                        accepted.quantity(),
                        accepted.idempotencyKey(),
                        principalId
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
                accepted.decidedAt(),
                null
        ));
    }

    private QuotaConsumptionRecord buildRejectedWithoutMeter(
            UUID tenantId,
            String principalId,
            String productKey,
            String meterKey,
            long quantity,
            Instant occurredAt,
            String idempotencyKey,
            String correlationId,
            Instant decidedAt
    ) {
        return new QuotaConsumptionRecord(
                UUID.randomUUID(),
                null,
                tenantId,
                principalId,
                productKey,
                meterKey,
                null,
                "",
                quantity,
                0L,
                occurredAt,
                null,
                null,
                idempotencyKey,
                correlationId,
                QuotaDecision.REJECTED,
                QuotaReasonCodes.UNKNOWN_METER,
                null,
                null,
                null,
                null,
                null,
                decidedAt
        );
    }

    private QuotaConsumptionRecord buildRejectedUnboundMeter(
            UUID tenantId,
            String principalId,
            String productKey,
            ActiveMeterDefinition meter,
            long quantity,
            Instant occurredAt,
            String idempotencyKey,
            String correlationId,
            Instant decidedAt
    ) {
        UsageWindow window = usageWindowResolver.resolve(occurredAt, meter.aggregationWindow());
        return new QuotaConsumptionRecord(
                UUID.randomUUID(),
                null,
                tenantId,
                principalId,
                productKey,
                meter.meterKey(),
                meter.meterDefinitionId(),
                "",
                quantity,
                0L,
                occurredAt,
                window.start(),
                window.end(),
                idempotencyKey,
                correlationId,
                QuotaDecision.REJECTED,
                QuotaReasonCodes.METER_NOT_BOUND_TO_FEATURE,
                null,
                null,
                null,
                null,
                null,
                decidedAt
        );
    }

    private static QuotaConsumptionRecord toRecord(
            DecisionPlan plan,
            UUID tenantId,
            String principalId,
            String productKey,
            ActiveMeterDefinition meter,
            long quantity,
            Instant occurredAt,
            UsageWindow window,
            String idempotencyKey,
            String correlationId,
            Instant decidedAt
    ) {
        UUID eventId = plan.decision() == QuotaDecision.ACCEPTED ? UUID.randomUUID() : null;
        return new QuotaConsumptionRecord(
                UUID.randomUUID(),
                eventId,
                tenantId,
                principalId,
                productKey,
                meter.meterKey(),
                meter.meterDefinitionId(),
                meter.featureKey(),
                quantity,
                plan.contribution(),
                occurredAt,
                window.start(),
                window.end(),
                idempotencyKey,
                correlationId,
                plan.decision(),
                plan.reason(),
                plan.configuredLimit(),
                plan.consumedAfter(),
                plan.remainingAfter(),
                plan.contractVersionId(),
                plan.contractVersionNumber(),
                decidedAt
        );
    }

    private QuotaConsumeResult replayOrConflict(
            QuotaConsumptionRecord existing,
            String productKey,
            String meterKey,
            long quantity,
            Instant occurredAt
    ) {
        if (!sameLogicalPayload(existing, productKey, meterKey, quantity, occurredAt)) {
            throw new IdempotencyConflictException(
                    "Idempotency key already used with a different usage payload"
            );
        }
        return toResult(existing, true);
    }

    private static boolean sameLogicalPayload(
            QuotaConsumptionRecord existing,
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

    private static QuotaConsumeResult toResult(QuotaConsumptionRecord record, boolean idempotentReplay) {
        return new QuotaConsumeResult(
                record.id(),
                record.eventId(),
                record.decision(),
                record.reason(),
                record.productKey(),
                record.meterKey(),
                record.featureKey(),
                record.quantity(),
                record.contribution(),
                record.configuredLimit(),
                record.consumedAfter(),
                record.remainingAfter(),
                record.contractVersionNumber(),
                record.correlationId(),
                idempotentReplay
        );
    }

    private record DecisionPlan(
            QuotaDecision decision,
            String reason,
            long contribution,
            Long configuredLimit,
            Long consumedAfter,
            Long remainingAfter,
            UUID contractVersionId,
            Integer contractVersionNumber
    ) {
    }
}
