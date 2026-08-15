package io.usagecore.entitlementruntime.application.entitlement;

import io.usagecore.entitlementruntime.application.observability.EntitlementRuntimeMetrics;
import io.usagecore.entitlementruntime.application.security.AuthenticatedPrincipal;
import io.usagecore.entitlementruntime.application.security.CorrelationIdAccessor;
import io.usagecore.entitlementruntime.application.security.CurrentPrincipal;
import io.usagecore.entitlementruntime.application.security.RuntimeAccessGuard;
import io.usagecore.entitlementruntime.domain.CommercialInvariantException;
import io.usagecore.entitlementruntime.domain.EntitlementDecisionType;
import io.usagecore.entitlementruntime.domain.EntitlementReasonCodes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates commercial entitlement for the authenticated tenant at clock.instant().
 * Commercial DENY outcomes are normal results, not exceptions.
 */
@Service
public class EntitlementCheckApplicationService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementCheckApplicationService.class);

    private final CurrentPrincipal currentPrincipal;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final CommercialEntitlementReader commercialEntitlementReader;
    private final EntitlementDecisionRecorder decisionRecorder;
    private final Clock clock;
    private final EntitlementRuntimeMetrics metrics;

    public EntitlementCheckApplicationService(
            CurrentPrincipal currentPrincipal,
            CorrelationIdAccessor correlationIdAccessor,
            CommercialEntitlementReader commercialEntitlementReader,
            EntitlementDecisionRecorder decisionRecorder,
            Clock clock,
            EntitlementRuntimeMetrics metrics
    ) {
        this.currentPrincipal = currentPrincipal;
        this.correlationIdAccessor = correlationIdAccessor;
        this.commercialEntitlementReader = commercialEntitlementReader;
        this.decisionRecorder = decisionRecorder;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public EntitlementCheckResult check(String productKey, String featureKey, long requestedUnits) {
        io.micrometer.core.instrument.Timer.Sample sample = metrics.startDecisionTimer();
        try {
            return checkInternal(productKey, featureKey, requestedUnits);
        } finally {
            metrics.stopDecisionTimer(sample);
        }
    }

    private EntitlementCheckResult checkInternal(String productKey, String featureKey, long requestedUnits) {
        if (requestedUnits <= 0) {
            throw new IllegalArgumentException("requestedUnits must be positive");
        }
        AuthenticatedPrincipal principal = currentPrincipal.require();
        RuntimeAccessGuard.requireEntitlementCheckAuthority(principal);
        UUID tenantId = principal.tenantId().orElseThrow();

        Instant evaluatedAt = clock.instant();
        String correlationId = correlationIdAccessor.currentCorrelationId();

        List<CommercialEntitlementMatch> matches = commercialEntitlementReader.findEffectiveEntitlements(
                tenantId,
                productKey,
                featureKey,
                evaluatedAt
        );

        if (matches.size() > 1) {
            throw new CommercialInvariantException(
                    "Multiple effective activated contract versions matched for the same commercial query"
            );
        }

        DecisionOutcome outcome = matches.isEmpty()
                ? denyNoActive()
                : decide(matches.getFirst(), requestedUnits);

        UUID decisionId = UUID.randomUUID();
        Instant createdAt = clock.instant();

        decisionRecorder.append(new EntitlementDecisionRecord(
                decisionId,
                tenantId,
                principal.subject(),
                outcome.contractId(),
                outcome.contractVersionId(),
                outcome.contractVersionNumber(),
                productKey,
                featureKey,
                requestedUnits,
                outcome.decision(),
                outcome.reason(),
                outcome.configuredLimit(),
                evaluatedAt,
                correlationId,
                createdAt
        ));

        metrics.recordDecision(
                outcome.decision() == null ? null : outcome.decision().name(),
                outcome.reason()
        );
        log.info(
                "Entitlement decision. decision={} reason={} correlationId={}",
                outcome.decision(),
                outcome.reason(),
                correlationId
        );

        return new EntitlementCheckResult(
                decisionId,
                outcome.decision(),
                outcome.reason(),
                productKey,
                featureKey,
                requestedUnits,
                outcome.configuredLimit(),
                outcome.contractVersionNumber(),
                evaluatedAt,
                correlationId
        );
    }

    private static DecisionOutcome denyNoActive() {
        return new DecisionOutcome(
                EntitlementDecisionType.DENY,
                EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT,
                null,
                null,
                null,
                null
        );
    }

    private static DecisionOutcome decide(CommercialEntitlementMatch match, long requestedUnits) {
        return switch (match.entitlementMode()) {
            case ENABLED -> new DecisionOutcome(
                    EntitlementDecisionType.ALLOW,
                    EntitlementReasonCodes.ENTITLEMENT_ENABLED,
                    match.contractId(),
                    match.contractVersionId(),
                    match.contractVersionNumber(),
                    null
            );
            case DISABLED -> new DecisionOutcome(
                    EntitlementDecisionType.DENY,
                    EntitlementReasonCodes.ENTITLEMENT_DISABLED,
                    match.contractId(),
                    match.contractVersionId(),
                    match.contractVersionNumber(),
                    null
            );
            case LIMITED -> decideLimited(match, requestedUnits);
        };
    }

    private static DecisionOutcome decideLimited(CommercialEntitlementMatch match, long requestedUnits) {
        Long configuredLimit = match.configuredLimit();
        if (configuredLimit == null || configuredLimit <= 0) {
            throw new CommercialInvariantException(
                    "LIMITED entitlement is missing a positive configuredLimit"
            );
        }
        if (requestedUnits > configuredLimit) {
            return new DecisionOutcome(
                    EntitlementDecisionType.DENY,
                    EntitlementReasonCodes.REQUEST_EXCEEDS_CONTRACT_LIMIT,
                    match.contractId(),
                    match.contractVersionId(),
                    match.contractVersionNumber(),
                    configuredLimit
            );
        }
        return new DecisionOutcome(
                EntitlementDecisionType.ALLOW_WITH_LIMIT,
                EntitlementReasonCodes.ENTITLEMENT_LIMITED,
                match.contractId(),
                match.contractVersionId(),
                match.contractVersionNumber(),
                configuredLimit
        );
    }

    private record DecisionOutcome(
            EntitlementDecisionType decision,
            String reason,
            UUID contractId,
            UUID contractVersionId,
            Integer contractVersionNumber,
            Long configuredLimit
    ) {
    }
}
