package io.usagecore.usagepipeline.application.reconciliation;

import io.usagecore.usagepipeline.application.commercial.CommercialPeriodStatus;
import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.security.CorrelationIdAccessor;
import io.usagecore.usagepipeline.application.security.CurrentPrincipal;
import io.usagecore.usagepipeline.application.security.PlatformRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8A reconciliation: read canonical evidence, rebuild expected commercial aggregates,
 * compare to persisted derived state, persist an immutable report. Never repairs aggregates,
 * quota, or period state. MATCH does not auto-finalize.
 * <p>
 * Transaction strategy:
 * <ol>
 *   <li>TX1 (REQUIRES_NEW): insert RUNNING — concurrent runs collide on partial unique index</li>
 *   <li>TX2: rebuild + insert items + COMPLETED</li>
 *   <li>On failure: TX3 (REQUIRES_NEW): mark FAILED with sanitized reason</li>
 * </ol>
 * READ COMMITTED is sufficient because RECONCILING/FINALIZED already block normal aggregate
 * mutation; this service never writes derived commercial state.
 */
@Service
public class ReconciliationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationApplicationService.class);
    private static final int FAILURE_REASON_MAX = 480;

    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationEvidenceReader evidenceReader;
    private final DeterministicRebuildEngine rebuildEngine;
    private final ReconciliationRunTransactions runTransactions;
    private final CurrentPrincipal currentPrincipal;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final Clock clock;

    public ReconciliationApplicationService(
            ReconciliationRepository reconciliationRepository,
            ReconciliationEvidenceReader evidenceReader,
            DeterministicRebuildEngine rebuildEngine,
            ReconciliationRunTransactions runTransactions,
            CurrentPrincipal currentPrincipal,
            CorrelationIdAccessor correlationIdAccessor,
            Clock clock
    ) {
        this.reconciliationRepository = reconciliationRepository;
        this.evidenceReader = evidenceReader;
        this.rebuildEngine = rebuildEngine;
        this.runTransactions = runTransactions;
        this.currentPrincipal = currentPrincipal;
        this.correlationIdAccessor = correlationIdAccessor;
        this.clock = clock;
    }

    public ReconciliationRunRecord startAndExecute(UUID commercialPeriodId) {
        Objects.requireNonNull(commercialPeriodId, "commercialPeriodId");
        AuthenticatedPrincipal principal = currentPrincipal.require();
        assertInitiateAuthority(principal);

        ReconciliationEvidenceReader.PeriodSnapshot period = evidenceReader
                .findPeriodById(commercialPeriodId)
                .orElseThrow(() -> new ReconciliationNotFoundException(
                        "Commercial period not found: " + commercialPeriodId
                ));
        assertTenantAccess(principal, period.tenantId(), "START_RECONCILIATION", commercialPeriodId);

        CommercialPeriodStatus status = CommercialPeriodStatus.valueOf(period.status());
        if (status == CommercialPeriodStatus.OPEN || status == CommercialPeriodStatus.CLOSING) {
            throw new ReconciliationConflictException(
                    "Reconciliation is only allowed for RECONCILING or FINALIZED commercial periods; status="
                            + status
            );
        }
        if (status != CommercialPeriodStatus.RECONCILING && status != CommercialPeriodStatus.FINALIZED) {
            throw new ReconciliationConflictException("Unsupported commercial period status: " + status);
        }

        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        String correlationId = correlationIdAccessor.currentCorrelationId();
        ReconciliationRunRecord running = new ReconciliationRunRecord(
                runId,
                period.tenantId(),
                period.productId(),
                period.id(),
                ReconciliationRunStatus.RUNNING,
                null,
                startedAt,
                null,
                principal.subject(),
                null,
                null,
                null,
                null,
                correlationId,
                null
        );

        try {
            runTransactions.insertRunning(running);
        } catch (DataIntegrityViolationException ex) {
            throw new ReconciliationConflictException(
                    "A reconciliation run is already RUNNING for commercial period " + commercialPeriodId
            );
        }

        try {
            return runTransactions.executeInTransaction(
                    () -> executeRebuild(runId, period, principal.subject(), correlationId, startedAt)
            );
        } catch (RuntimeException ex) {
            String reason = sanitizeFailureReason(ex);
            try {
                runTransactions.markFailed(runId, clock.instant(), reason);
            } catch (RuntimeException markEx) {
                log.error(
                        "Failed to mark reconciliation run FAILED after calculation error. runId={}",
                        runId,
                        markEx
                );
            }
            log.warn(
                    "Reconciliation run failed. runId={} commercialPeriodId={} reason={}",
                    runId,
                    commercialPeriodId,
                    reason
            );
            if (ex instanceof ReconciliationConflictException || ex instanceof ReconciliationNotFoundException) {
                throw ex;
            }
            return reconciliationRepository.findRunById(runId).orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public ReconciliationRunRecord requireRun(UUID runId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        assertReadAuthority(principal);
        ReconciliationRunRecord run = reconciliationRepository.findRunById(runId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Reconciliation run not found: " + runId));
        assertTenantAccess(principal, run.tenantId(), "READ_RECONCILIATION", runId);
        return run;
    }

    @Transactional(readOnly = true)
    public List<ReconciliationItemRecord> requireItems(UUID runId) {
        requireRun(runId);
        return reconciliationRepository.findItemsByRunId(runId);
    }

    private ReconciliationRunRecord executeRebuild(
            UUID runId,
            ReconciliationEvidenceReader.PeriodSnapshot periodHint,
            String startedBy,
            String correlationId,
            Instant startedAt
    ) {
        ReconciliationEvidenceReader.PeriodSnapshot period = evidenceReader
                .findPeriodByIdForShare(periodHint.id())
                .orElseThrow(() -> new ReconciliationNotFoundException(
                        "Commercial period not found: " + periodHint.id()
                ));
        CommercialPeriodStatus status = CommercialPeriodStatus.valueOf(period.status());
        if (status != CommercialPeriodStatus.RECONCILING && status != CommercialPeriodStatus.FINALIZED) {
            throw new ReconciliationConflictException(
                    "Commercial period status changed before reconciliation could complete; status=" + status
            );
        }

        String productKey = evidenceReader.requireProductKey(period.productId());
        List<ReconciliationEvidenceReader.MeterSnapshot> meters =
                evidenceReader.findActiveMetersByProductId(period.productId());
        List<ReconciliationEvidenceReader.LedgerEventSnapshot> ledgerEvents = evidenceReader.findLedgerEvents(
                period.tenantId(),
                productKey,
                period.periodStart(),
                period.periodEnd()
        );
        Set<UUID> quarantined = evidenceReader.findQuarantinedEventIds(period.id());

        List<ReconciliationEvidenceReader.WindowAggregateSnapshot> persisted = meters.stream()
                .flatMap(meter -> evidenceReader.findWindowAggregatesOverlapping(
                        period.tenantId(),
                        meter.meterDefinitionId(),
                        period.periodStart(),
                        period.periodEnd()
                ).stream())
                .toList();

        DeterministicRebuildEngine.RebuildResult rebuild = rebuildEngine.rebuild(
                period,
                meters,
                ledgerEvents,
                quarantined,
                persisted,
                evidenceReader::findQuotaConsumed
        );

        Instant completedAt = clock.instant();
        List<ReconciliationItemRecord> items = rebuild.items().stream()
                .map(item -> new ReconciliationItemRecord(
                        item.id(),
                        runId,
                        item.meterDefinitionId(),
                        item.meterKey(),
                        item.aggregationType(),
                        item.windowStart(),
                        item.windowEnd(),
                        item.observedExpectedValue(),
                        item.commercialExpectedValue(),
                        item.actualValue(),
                        item.difference(),
                        item.expectedEventCount(),
                        item.actualEventCount(),
                        item.quarantinedEventCount(),
                        item.observedEventCount(),
                        item.quotaConsumedValue(),
                        item.status(),
                        item.classification()
                ))
                .toList();

        ReconciliationRunRecord completed = new ReconciliationRunRecord(
                runId,
                period.tenantId(),
                period.productId(),
                period.id(),
                ReconciliationRunStatus.COMPLETED,
                rebuild.result(),
                startedAt,
                completedAt,
                startedBy,
                rebuild.canonicalEventCount(),
                rebuild.quarantinedEventCount(),
                rebuild.matchedMeterCount(),
                rebuild.mismatchedMeterCount(),
                correlationId,
                null
        );
        reconciliationRepository.complete(completed, items);

        log.info(
                "Reconciliation completed. runId={} commercialPeriodId={} result={} matched={} mismatched={} "
                        + "canonicalEvents={} quarantinedEvents={}",
                runId,
                period.id(),
                rebuild.result(),
                rebuild.matchedMeterCount(),
                rebuild.mismatchedMeterCount(),
                rebuild.canonicalEventCount(),
                rebuild.quarantinedEventCount()
        );
        return completed;
    }

    private static void assertInitiateAuthority(AuthenticatedPrincipal principal) {
        if (principal.hasRole(PlatformRole.PLATFORM_ADMIN) || principal.hasRole(PlatformRole.BILLING_OPERATOR)) {
            return;
        }
        throw new AuthorizationDeniedException("Caller lacks a permitted role to initiate reconciliation");
    }

    private static void assertReadAuthority(AuthenticatedPrincipal principal) {
        if (principal.hasRole(PlatformRole.PLATFORM_ADMIN)
                || principal.hasRole(PlatformRole.BILLING_OPERATOR)
                || principal.hasRole(PlatformRole.AUDITOR)) {
            return;
        }
        throw new AuthorizationDeniedException("Caller lacks a permitted role to read reconciliation");
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

    static String sanitizeFailureReason(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        String sanitized = message
                .replaceAll("(?i)password[=:].*", "password=[redacted]")
                .replaceAll("(?i)secret[=:].*", "secret=[redacted]")
                .trim();
        if (sanitized.length() > FAILURE_REASON_MAX) {
            sanitized = sanitized.substring(0, FAILURE_REASON_MAX);
        }
        return sanitized;
    }
}
