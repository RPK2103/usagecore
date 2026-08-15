package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.application.observability.ControlPlaneMetrics;
import io.usagecore.controlplane.application.security.AuthenticatedPrincipal;
import io.usagecore.controlplane.application.security.CorrelationIdAccessor;
import io.usagecore.controlplane.application.security.CurrentPrincipal;
import io.usagecore.controlplane.application.security.PlatformRole;
import io.usagecore.controlplane.application.security.TenantAccessGuard;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriod;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriodStatus;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriodTransition;
import io.usagecore.controlplane.domain.catalogue.DomainInvariantException;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative commercial period lifecycle. Transitions use PostgreSQL conditional UPDATE
 * as concurrency authority. Finalization is manual and does not prove reconciliation.
 */
@Service
public class CommercialPeriodApplicationService {

    private static final Logger log = LoggerFactory.getLogger(CommercialPeriodApplicationService.class);

    private final CommercialPeriodRepository commercialPeriodRepository;
    private final TenantRepository tenantRepository;
    private final ProductRepository productRepository;
    private final CurrentPrincipal currentPrincipal;
    private final TenantAccessGuard tenantAccessGuard;
    private final CorrelationIdAccessor correlationIdAccessor;
    private final ActiveReconciliationProbe activeReconciliationProbe;
    private final Clock clock;
    private final ControlPlaneMetrics metrics;

    public CommercialPeriodApplicationService(
            CommercialPeriodRepository commercialPeriodRepository,
            TenantRepository tenantRepository,
            ProductRepository productRepository,
            CurrentPrincipal currentPrincipal,
            TenantAccessGuard tenantAccessGuard,
            CorrelationIdAccessor correlationIdAccessor,
            ActiveReconciliationProbe activeReconciliationProbe,
            Clock clock,
            ControlPlaneMetrics metrics
    ) {
        this.commercialPeriodRepository = commercialPeriodRepository;
        this.tenantRepository = tenantRepository;
        this.productRepository = productRepository;
        this.currentPrincipal = currentPrincipal;
        this.tenantAccessGuard = tenantAccessGuard;
        this.correlationIdAccessor = correlationIdAccessor;
        this.activeReconciliationProbe = activeReconciliationProbe;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public CommercialPeriod create(UUID tenantId, UUID productId, Instant periodStart, Instant periodEnd) {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        tenantAccessGuard.assertHasAnyRole(
                "CREATE_COMMERCIAL_PERIOD",
                "CommercialPeriod",
                null,
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.BILLING_OPERATOR
        );
        UUID effectiveTenantId = tenantAccessGuard.resolveWritableTenantId(tenantId, "CREATE_COMMERCIAL_PERIOD");
        Tenant tenant = tenantRepository.findById(effectiveTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + effectiveTenantId));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        CommercialPeriod period = CommercialPeriod.create(tenant.id(), product.id(), periodStart, periodEnd);
        return commercialPeriodRepository.saveNew(period);
    }

    @Transactional(readOnly = true)
    public CommercialPeriod require(UUID tenantId, UUID productId, UUID periodId) {
        tenantAccessGuard.assertHasAnyRole(
                "READ_COMMERCIAL_PERIOD",
                "CommercialPeriod",
                periodId.toString(),
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.BILLING_OPERATOR,
                PlatformRole.CONTRACT_MANAGER,
                PlatformRole.TENANT_ADMIN,
                PlatformRole.AUDITOR
        );
        UUID effectiveTenantId = resolveReadableTenantId(tenantId, "READ_COMMERCIAL_PERIOD", periodId);
        return commercialPeriodRepository
                .findByIdAndTenantIdAndProductId(periodId, effectiveTenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Commercial period not found: " + periodId));
    }

    @Transactional
    public CommercialPeriod beginClosing(UUID tenantId, UUID productId, UUID periodId) {
        return transition(
                tenantId,
                productId,
                periodId,
                "CLOSE_COMMERCIAL_PERIOD",
                CommercialPeriodStatus.OPEN,
                CommercialPeriodStatus.CLOSING,
                null
        );
    }

    @Transactional
    public CommercialPeriod beginReconciling(UUID tenantId, UUID productId, UUID periodId) {
        return transition(
                tenantId,
                productId,
                periodId,
                "RECONCILE_COMMERCIAL_PERIOD",
                CommercialPeriodStatus.CLOSING,
                CommercialPeriodStatus.RECONCILING,
                null
        );
    }

    @Transactional
    public CommercialPeriod finalize(UUID tenantId, UUID productId, UUID periodId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return transition(
                tenantId,
                productId,
                periodId,
                "FINALIZE_COMMERCIAL_PERIOD",
                CommercialPeriodStatus.RECONCILING,
                CommercialPeriodStatus.FINALIZED,
                principal.subject()
        );
    }

    @Transactional(readOnly = true)
    public List<CommercialPeriodTransition> listTransitions(UUID tenantId, UUID productId, UUID periodId) {
        require(tenantId, productId, periodId);
        return commercialPeriodRepository.findTransitionsByPeriodId(periodId);
    }

    private CommercialPeriod transition(
            UUID tenantId,
            UUID productId,
            UUID periodId,
            String action,
            CommercialPeriodStatus fromStatus,
            CommercialPeriodStatus toStatus,
            String finalizedByOrNull
    ) {
        tenantAccessGuard.assertHasAnyRole(
                action,
                "CommercialPeriod",
                periodId.toString(),
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.BILLING_OPERATOR
        );
        UUID effectiveTenantId = tenantAccessGuard.resolveWritableTenantId(tenantId, action);
        CommercialPeriod existing = commercialPeriodRepository
                .findByIdAndTenantIdAndProductId(periodId, effectiveTenantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Commercial period not found: " + periodId));

        if (existing.status() == CommercialPeriodStatus.FINALIZED) {
            metrics.recordPeriodTransition(existing.status().name(), toStatus.name(), "rejected");
            throw new DomainInvariantException(
                    "FINALIZED commercial period is terminal and cannot transition to " + toStatus
            );
        }
        if (existing.status() != fromStatus) {
            metrics.recordPeriodTransition(existing.status().name(), toStatus.name(), "rejected");
            throw new DomainInvariantException(
                    "Invalid commercial period transition from " + existing.status() + " to " + toStatus
            );
        }
        if (toStatus == CommercialPeriodStatus.FINALIZED) {
            Objects.requireNonNull(finalizedByOrNull, "finalizedBy");
            if (finalizedByOrNull.isBlank()) {
                throw new DomainInvariantException("finalizedBy principal is required");
            }
            if (activeReconciliationProbe.hasRunningReconciliation(periodId)) {
                metrics.recordPeriodTransition(fromStatus.name(), toStatus.name(), "rejected");
                throw new DomainInvariantException(
                        "Cannot finalize commercial period while a reconciliation run is RUNNING"
                );
            }
        }

        Instant now = clock.instant();
        AuthenticatedPrincipal principal = currentPrincipal.require();

        Optional<CommercialPeriod> updated = commercialPeriodRepository.transitionIfStatus(
                periodId,
                fromStatus,
                toStatus,
                now,
                finalizedByOrNull
        );
        if (updated.isEmpty()) {
            metrics.recordPeriodTransition(fromStatus.name(), toStatus.name(), "rejected");
            throw new DomainInvariantException(
                    "Invalid commercial period transition from " + fromStatus + " to " + toStatus
            );
        }

        commercialPeriodRepository.appendTransition(new CommercialPeriodTransition(
                UUID.randomUUID(),
                periodId,
                fromStatus,
                toStatus,
                principal.subject(),
                now,
                correlationIdAccessor.currentCorrelationId()
        ));
        metrics.recordPeriodTransition(fromStatus.name(), toStatus.name(), "success");
        log.info(
                "Commercial period transitioned. from={} to={} correlationId={}",
                fromStatus,
                toStatus,
                correlationIdAccessor.currentCorrelationId()
        );
        return updated.get();
    }

    private UUID resolveReadableTenantId(UUID requestedTenantId, String action, UUID periodId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        if (principal.isPlatformAdmin()) {
            return requestedTenantId;
        }
        tenantAccessGuard.assertCanAccessTenant(
                requestedTenantId,
                action,
                "CommercialPeriod",
                periodId.toString()
        );
        return requestedTenantId;
    }
}
