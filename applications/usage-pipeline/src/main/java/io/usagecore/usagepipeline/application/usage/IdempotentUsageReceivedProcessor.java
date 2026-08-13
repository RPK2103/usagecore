package io.usagecore.usagepipeline.application.usage;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodReader;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodStatus;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodView;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionReasons;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRecord;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer processing for {@code UsageReceived}.
 * <p>
 * Deduplicates by Kafka envelope {@code eventId} (not HTTP {@code idempotencyKey}).
 * Claims {@code processed_event}, inserts {@code usage_ledger}, and either applies
 * lifetime/window aggregates (OPEN / CLOSING / NO_PERIOD) or records a commercial
 * usage exception without aggregate mutation (RECONCILING / FINALIZED) — all in one
 * PostgreSQL transaction. Duplicate redelivery is a successful no-op.
 * Delivery remains at-least-once. HTTP 202 meaning is unchanged.
 */
@Service
public class IdempotentUsageReceivedProcessor implements UsageReceivedProcessor {

    public static final String CONSUMER_NAME = "usage-received-processor-v1";

    private static final Logger log = LoggerFactory.getLogger(IdempotentUsageReceivedProcessor.class);

    private final ProcessedEventRepository processedEventRepository;
    private final UsageLedgerRepository usageLedgerRepository;
    private final MeterDefinitionLookup meterDefinitionLookup;
    private final UsageAggregateRepository usageAggregateRepository;
    private final UsageWindowAggregateRepository usageWindowAggregateRepository;
    private final UsageWindowResolver usageWindowResolver;
    private final CommercialPeriodReader commercialPeriodReader;
    private final CommercialUsageExceptionRepository commercialUsageExceptionRepository;
    private final Clock clock;

    public IdempotentUsageReceivedProcessor(
            ProcessedEventRepository processedEventRepository,
            UsageLedgerRepository usageLedgerRepository,
            MeterDefinitionLookup meterDefinitionLookup,
            UsageAggregateRepository usageAggregateRepository,
            UsageWindowAggregateRepository usageWindowAggregateRepository,
            UsageWindowResolver usageWindowResolver,
            CommercialPeriodReader commercialPeriodReader,
            CommercialUsageExceptionRepository commercialUsageExceptionRepository,
            Clock clock
    ) {
        this.processedEventRepository = processedEventRepository;
        this.usageLedgerRepository = usageLedgerRepository;
        this.meterDefinitionLookup = meterDefinitionLookup;
        this.usageAggregateRepository = usageAggregateRepository;
        this.usageWindowAggregateRepository = usageWindowAggregateRepository;
        this.usageWindowResolver = usageWindowResolver;
        this.commercialPeriodReader = commercialPeriodReader;
        this.commercialUsageExceptionRepository = commercialUsageExceptionRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void process(EventEnvelope<UsageReceivedPayload> event) {
        validateSupportedContract(event);
        validateLedgerFields(event);

        Instant processedAt = clock.instant();
        boolean claimed = processedEventRepository.tryClaim(new ProcessedEventRecord(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.tenantId(),
                CONSUMER_NAME,
                processedAt,
                event.correlationId()
        ));

        if (!claimed) {
            log.debug(
                    "Duplicate UsageReceived ignored (eventId already processed). eventId={} tenantId={}",
                    event.eventId(),
                    event.tenantId()
            );
            return;
        }

        UsageReceivedPayload payload = event.payload();
        ActiveMeterDefinition meter = meterDefinitionLookup
                .findActiveByProductKeyAndMeterKey(payload.productKey(), payload.meterKey())
                .orElseThrow(() -> new UnknownUsageMeterException(
                        "Unknown or inactive meter: productKey="
                                + payload.productKey()
                                + " meterKey="
                                + payload.meterKey()
                ));

        UsageWindow window = usageWindowResolver.resolve(event.occurredAt(), meter.aggregationWindow());
        boolean late = window.isLate(processedAt);

        // Ledger evidence is always written for claimed events (canonical occurrence history).
        usageLedgerRepository.insert(new UsageLedgerRecord(
                UUID.randomUUID(),
                event.eventId(),
                event.tenantId(),
                payload.productKey(),
                payload.meterKey(),
                payload.quantity(),
                event.occurredAt(),
                payload.idempotencyKey(),
                event.correlationId(),
                payload.principalSubject(),
                processedAt,
                late
        ));

        Optional<CommercialPeriodView> periodOpt = commercialPeriodReader.findCoveringForShare(
                event.tenantId(),
                meter.productId(),
                event.occurredAt()
        );

        if (periodOpt.isPresent()) {
            CommercialPeriodView period = periodOpt.get();
            if (period.status() == CommercialPeriodStatus.RECONCILING
                    || period.status() == CommercialPeriodStatus.FINALIZED) {
                String reason = period.status() == CommercialPeriodStatus.FINALIZED
                        ? CommercialUsageExceptionReasons.PERIOD_FINALIZED
                        : CommercialUsageExceptionReasons.PERIOD_RECONCILING;
                commercialUsageExceptionRepository.insertIfAbsent(new CommercialUsageExceptionRecord(
                        UUID.randomUUID(),
                        event.eventId(),
                        event.tenantId(),
                        meter.productId(),
                        meter.meterDefinitionId(),
                        period.id(),
                        reason,
                        event.occurredAt(),
                        processedAt,
                        event.correlationId()
                ));
                log.info(
                        "UsageReceived quarantined (commercial period blocks aggregate mutation). "
                                + "eventId={} tenantId={} commercialPeriodId={} status={} reason={} "
                                + "productKey={} meterKey={} late={} quantity={} correlationId={}",
                        event.eventId(),
                        event.tenantId(),
                        period.id(),
                        period.status(),
                        reason,
                        payload.productKey(),
                        payload.meterKey(),
                        late,
                        payload.quantity(),
                        event.correlationId()
                );
                return;
            }
            // OPEN / CLOSING: apply aggregates (CLOSING still accepts controlled late arrivals).
            log.debug(
                    "UsageReceived under commercial period. eventId={} commercialPeriodId={} status={}",
                    event.eventId(),
                    period.id(),
                    period.status()
            );
        }
        // NO_PERIOD: Phase 6 compatibility — lifecycle enforcement inactive for this time.

        usageAggregateRepository.applyEvent(
                event.tenantId(),
                meter,
                payload.quantity(),
                event.occurredAt(),
                processedAt
        );

        usageWindowAggregateRepository.applyEvent(
                event.tenantId(),
                meter,
                window,
                payload.quantity(),
                event.occurredAt(),
                processedAt
        );

        log.info(
                "UsageReceived recorded to ledger, lifetime aggregate, and window aggregate. "
                        + "eventId={} tenantId={} productKey={} meterKey={} aggregationType={} "
                        + "aggregationWindow={} windowStart={} windowEnd={} late={} quantity={} "
                        + "idempotencyKey={} correlationId={}",
                event.eventId(),
                event.tenantId(),
                payload.productKey(),
                payload.meterKey(),
                meter.aggregationType(),
                meter.aggregationWindow(),
                window.start(),
                window.end(),
                late,
                payload.quantity(),
                payload.idempotencyKey(),
                event.correlationId()
        );
    }

    public static void validateSupportedContract(EventEnvelope<UsageReceivedPayload> event) {
        if (event == null) {
            throw new InvalidUsageEventException("Event envelope is required");
        }
        if (!EventTypes.USAGE_RECEIVED.equals(event.eventType())) {
            throw new UnsupportedUsageEventException(
                    "Unsupported eventType: " + event.eventType()
            );
        }
        if (!EventVersions.V1.equals(event.eventVersion())) {
            throw new UnsupportedUsageEventException(
                    "Unsupported eventVersion: " + event.eventVersion()
            );
        }
    }

    static void validateLedgerFields(EventEnvelope<UsageReceivedPayload> event) {
        if (event.eventId() == null) {
            throw new InvalidUsageEventException("eventId is required");
        }
        if (event.tenantId() == null) {
            throw new InvalidUsageEventException("tenantId is required");
        }
        if (event.occurredAt() == null) {
            throw new InvalidUsageEventException("occurredAt is required");
        }
        UsageReceivedPayload payload = event.payload();
        if (payload == null) {
            throw new InvalidUsageEventException("payload is required");
        }
        if (payload.productKey() == null || payload.productKey().isBlank()) {
            throw new InvalidUsageEventException("productKey is required");
        }
        if (payload.meterKey() == null || payload.meterKey().isBlank()) {
            throw new InvalidUsageEventException("meterKey is required");
        }
        if (payload.quantity() <= 0) {
            throw new InvalidUsageEventException("quantity must be positive");
        }
        if (payload.idempotencyKey() == null || payload.idempotencyKey().isBlank()) {
            throw new InvalidUsageEventException("idempotencyKey is required");
        }
    }
}
