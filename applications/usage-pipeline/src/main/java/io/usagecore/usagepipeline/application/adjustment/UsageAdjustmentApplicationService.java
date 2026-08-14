package io.usagecore.usagepipeline.application.adjustment;

import io.usagecore.usagepipeline.application.commercial.CommercialPeriodStatus;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationEvidenceReader;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationNotFoundException;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRepository;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunRecord;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunStatus;
import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.security.CorrelationIdAccessor;
import io.usagecore.usagepipeline.application.security.CurrentPrincipal;
import io.usagecore.usagepipeline.application.security.PlatformRole;
import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.IdempotencyConflictException;
import io.usagecore.usagepipeline.application.usage.MeterDefinitionLookup;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRepository;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRecord;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRepository;
import io.usagecore.usagepipeline.application.usage.UsageWindow;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
import io.usagecore.usagepipeline.application.usage.UsageWindowResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8B: apply quarantined canonical usage to derived commercial aggregates via an
 * explicit immutable UsageAdjustment. Never mutates usage_ledger, commercial_usage_exception,
 * quota_state, or previous reconciliation reports. Does not publish Kafka.
 * <p>
 * One PostgreSQL transaction: period FOR UPDATE serializes with reconciliation FOR SHARE;
 * UNIQUE(commercial_usage_exception_id) is the authority for one-application-per-exception.
 */
@Service
public class UsageAdjustmentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UsageAdjustmentApplicationService.class);
    static final int REASON_MAX = 512;
    static final int IDEMPOTENCY_KEY_MAX = 128;

    private final UsageAdjustmentRepository adjustmentRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final UsageLedgerRepository usageLedgerRepository;
    private final MeterDefinitionLookup meterDefinitionLookup;
    private final UsageAggregateRepository usageAggregateRepository;
    private final UsageWindowAggregateRepository usageWindowAggregateRepository;
    private final UsageWindowResolver usageWindowResolver;
    private final CurrentPrincipal currentPrincipal;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final Clock clock;

    public UsageAdjustmentApplicationService(
            UsageAdjustmentRepository adjustmentRepository,
            ReconciliationRepository reconciliationRepository,
            UsageLedgerRepository usageLedgerRepository,
            MeterDefinitionLookup meterDefinitionLookup,
            UsageAggregateRepository usageAggregateRepository,
            UsageWindowAggregateRepository usageWindowAggregateRepository,
            UsageWindowResolver usageWindowResolver,
            CurrentPrincipal currentPrincipal,
            CorrelationIdAccessor correlationIdAccessor,
            Clock clock
    ) {
        this.adjustmentRepository = adjustmentRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.usageLedgerRepository = usageLedgerRepository;
        this.meterDefinitionLookup = meterDefinitionLookup;
        this.usageAggregateRepository = usageAggregateRepository;
        this.usageWindowAggregateRepository = usageWindowAggregateRepository;
        this.usageWindowResolver = usageWindowResolver;
        this.currentPrincipal = currentPrincipal;
        this.correlationIdAccessor = correlationIdAccessor;
        this.clock = clock;
    }

    @Transactional
    public UsageAdjustmentRecord applyQuarantinedUsage(UUID runId, UUID exceptionId, String idempotencyKey, String reason) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(exceptionId, "exceptionId");
        AuthenticatedPrincipal principal = currentPrincipal.require();
        assertApplyAuthority(principal);
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        String normalizedReason = requireReason(reason);

        ReconciliationRunRecord runHint = reconciliationRepository.findRunById(runId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation run not found: " + runId));
        assertTenantAccess(principal, runHint.tenantId(), "APPLY_ADJUSTMENT", runId);

        ReconciliationEvidenceReader.PeriodSnapshot period =
                adjustmentRepository.lockPeriodForUpdate(runHint.commercialPeriodId());
        assertTenantAccess(principal, period.tenantId(), "APPLY_ADJUSTMENT", period.id());

        ReconciliationRunRecord run = reconciliationRepository.findRunByIdForUpdate(runId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation run not found: " + runId));
        if (run.status() != ReconciliationRunStatus.COMPLETED) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.RECONCILIATION_RUN_NOT_COMPLETED,
                    "Adjustment requires a COMPLETED reconciliation run; status=" + run.status()
            );
        }
        if (!run.tenantId().equals(period.tenantId()) || !run.commercialPeriodId().equals(period.id())) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.RECONCILIATION_RUN_NOT_COMPLETED,
                    "Reconciliation run does not belong to the locked commercial period"
            );
        }

        if (reconciliationRepository.existsRunningForPeriod(period.id())) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION,
                    "Cannot apply adjustment while a reconciliation run is RUNNING for this commercial period"
            );
        }

        CommercialPeriodStatus periodStatus = CommercialPeriodStatus.valueOf(period.status());
        if (periodStatus == CommercialPeriodStatus.OPEN || periodStatus == CommercialPeriodStatus.CLOSING) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD,
                    "UsageAdjustment is only allowed for RECONCILING or FINALIZED periods; status=" + periodStatus
            );
        }
        if (periodStatus != CommercialPeriodStatus.RECONCILING && periodStatus != CommercialPeriodStatus.FINALIZED) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD,
                    "Unsupported commercial period status: " + periodStatus
            );
        }

        UsageAdjustmentRepository.ExceptionSnapshot exception = adjustmentRepository
                .lockExceptionForUpdate(exceptionId)
                .orElseThrow(() -> new AdjustmentNotFoundException(
                        "Commercial usage exception not found: " + exceptionId
                ));
        if (!exception.tenantId().equals(run.tenantId())
                || !exception.commercialPeriodId().equals(run.commercialPeriodId())) {
            throw new AdjustmentNotFoundException("Commercial usage exception not found: " + exceptionId);
        }

        Optional<UsageAdjustmentRecord> byKey =
                adjustmentRepository.findByTenantIdAndIdempotencyKey(run.tenantId(), normalizedKey);
        if (byKey.isPresent()) {
            return replayOrConflict(byKey.get(), runId, exceptionId, normalizedReason);
        }
        Optional<UsageAdjustmentRecord> byException =
                adjustmentRepository.findByCommercialUsageExceptionId(exceptionId);
        if (byException.isPresent()) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_ALREADY_APPLIED,
                    "Commercial usage exception already has an applied UsageAdjustment"
            );
        }

        UsageLedgerRecord ledger = usageLedgerRepository.findByEventId(exception.eventId())
                .orElseThrow(() -> new AdjustmentNotFoundException(
                        "Canonical usage event not found for exception: " + exceptionId
                ));
        if (!ledger.tenantId().equals(run.tenantId())) {
            throw new AdjustmentNotFoundException("Canonical usage event not found for exception: " + exceptionId);
        }
        if (ledger.occurredAt().isBefore(period.periodStart()) || !ledger.occurredAt().isBefore(period.periodEnd())) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD,
                    "Canonical usage event is outside the commercial period bounds"
            );
        }

        ActiveMeterDefinition meter = meterDefinitionLookup
                .findActiveByMeterDefinitionId(exception.meterDefinitionId())
                .orElseThrow(() -> new AdjustmentConflictException(
                        AdjustmentErrorCodes.ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD,
                        "Active meter definition not found for exception " + exceptionId
                ));
        if (!meter.meterKey().equals(ledger.meterKey()) || !meter.productKey().equals(ledger.productKey())) {
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD,
                    "Exception meter does not match canonical ledger event"
            );
        }

        UsageWindow window = usageWindowResolver.resolve(ledger.occurredAt(), meter.aggregationWindow());
        long aggregateContribution = switch (meter.aggregationType()) {
            case SUM, MAX -> ledger.quantity();
            case COUNT -> 1L;
        };

        Instant appliedAt = clock.instant();
        String correlationId = correlationIdAccessor.currentCorrelationId();
        UsageAdjustmentRecord pending = new UsageAdjustmentRecord(
                UUID.randomUUID(),
                run.tenantId(),
                period.productId(),
                meter.meterDefinitionId(),
                meter.meterKey(),
                period.id(),
                exception.id(),
                ledger.eventId(),
                run.id(),
                AdjustmentType.APPLY_QUARANTINED_USAGE,
                meter.aggregationType(),
                ledger.quantity(),
                aggregateContribution,
                1L,
                window.start(),
                window.end(),
                normalizedKey,
                normalizedReason,
                appliedAt,
                principal.subject(),
                correlationId
        );

        Optional<UsageAdjustmentRecord> inserted = adjustmentRepository.insert(pending);
        if (inserted.isEmpty()) {
            Optional<UsageAdjustmentRecord> racedKey =
                    adjustmentRepository.findByTenantIdAndIdempotencyKey(run.tenantId(), normalizedKey);
            if (racedKey.isPresent()) {
                return replayOrConflict(racedKey.get(), runId, exceptionId, normalizedReason);
            }
            Optional<UsageAdjustmentRecord> racedException =
                    adjustmentRepository.findByCommercialUsageExceptionId(exceptionId);
            if (racedException.isPresent()) {
                if (racedException.get().idempotencyKey().equals(normalizedKey)
                        && racedException.get().reconciliationRunId().equals(runId)
                        && racedException.get().reason().equals(normalizedReason)) {
                    return racedException.get();
                }
                throw new AdjustmentConflictException(
                        AdjustmentErrorCodes.ADJUSTMENT_ALREADY_APPLIED,
                        "Commercial usage exception already has an applied UsageAdjustment"
                );
            }
            throw new AdjustmentConflictException(
                    AdjustmentErrorCodes.ADJUSTMENT_ALREADY_APPLIED,
                    "UsageAdjustment unique constraint rejected the insert"
            );
        }

        usageAggregateRepository.applyEvent(
                run.tenantId(),
                meter,
                ledger.quantity(),
                ledger.occurredAt(),
                appliedAt
        );
        usageWindowAggregateRepository.applyEvent(
                run.tenantId(),
                meter,
                window,
                ledger.quantity(),
                ledger.occurredAt(),
                appliedAt
        );

        log.info(
                "UsageAdjustment applied. adjustmentId={} sourceEventId={} commercialPeriodId={} "
                        + "reconciliationRunId={} tenantId={} correlationId={} aggregationType={}",
                pending.id(),
                ledger.eventId(),
                period.id(),
                run.id(),
                run.tenantId(),
                correlationId,
                meter.aggregationType()
        );
        return pending;
    }

    @Transactional(readOnly = true)
    public UsageAdjustmentRecord requireAdjustment(UUID adjustmentId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        assertReadAuthority(principal);
        UsageAdjustmentRecord record = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new AdjustmentNotFoundException("UsageAdjustment not found: " + adjustmentId));
        assertTenantAccess(principal, record.tenantId(), "READ_ADJUSTMENT", adjustmentId);
        return record;
    }

    private static UsageAdjustmentRecord replayOrConflict(
            UsageAdjustmentRecord existing,
            UUID runId,
            UUID exceptionId,
            String reason
    ) {
        boolean sameLogical = existing.reconciliationRunId().equals(runId)
                && existing.commercialUsageExceptionId().equals(exceptionId)
                && existing.adjustmentType() == AdjustmentType.APPLY_QUARANTINED_USAGE
                && existing.reason().equals(reason);
        if (sameLogical) {
            return existing;
        }
        throw new IdempotencyConflictException(
                "Idempotency key already used with a different adjustment request"
        );
    }

    static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > IDEMPOTENCY_KEY_MAX || containsControlChars(trimmed)) {
            throw new IllegalArgumentException("idempotencyKey is invalid");
        }
        return trimmed;
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX || containsControlChars(trimmed)) {
            throw new IllegalArgumentException("reason is invalid");
        }
        return trimmed;
    }

    private static boolean containsControlChars(String value) {
        return value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\t');
    }

    private static void assertApplyAuthority(AuthenticatedPrincipal principal) {
        if (principal.hasRole(PlatformRole.PLATFORM_ADMIN) || principal.hasRole(PlatformRole.BILLING_OPERATOR)) {
            return;
        }
        throw new AuthorizationDeniedException("Caller lacks a permitted role to apply usage adjustments");
    }

    private static void assertReadAuthority(AuthenticatedPrincipal principal) {
        if (principal.hasRole(PlatformRole.PLATFORM_ADMIN)
                || principal.hasRole(PlatformRole.BILLING_OPERATOR)
                || principal.hasRole(PlatformRole.AUDITOR)) {
            return;
        }
        throw new AuthorizationDeniedException("Caller lacks a permitted role to read usage adjustments");
    }

    private static void assertTenantAccess(
            AuthenticatedPrincipal principal,
            UUID resourceTenantId,
            String action,
            UUID resourceId
    ) {
        if (principal.hasRole(PlatformRole.PLATFORM_ADMIN)) {
            return;
        }
        if (principal.tenantId().isEmpty() || !principal.tenantId().get().equals(resourceTenantId)) {
            throw new AuthorizationDeniedException(
                    "Access denied for " + action + " on resource " + resourceId
            );
        }
    }
}
