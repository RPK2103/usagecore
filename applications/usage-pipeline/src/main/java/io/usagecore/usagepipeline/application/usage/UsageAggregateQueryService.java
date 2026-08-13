package io.usagecore.usagepipeline.application.usage;

import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.security.CurrentPrincipal;
import io.usagecore.usagepipeline.application.security.PlatformRole;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped read of derived usage aggregates. Tenant always comes from JWT.
 */
@Service
public class UsageAggregateQueryService {

    private final CurrentPrincipal currentPrincipal;
    private final UsageAggregateRepository usageAggregateRepository;

    public UsageAggregateQueryService(
            CurrentPrincipal currentPrincipal,
            UsageAggregateRepository usageAggregateRepository
    ) {
        this.currentPrincipal = currentPrincipal;
        this.usageAggregateRepository = usageAggregateRepository;
    }

    @Transactional(readOnly = true)
    public UsageAggregateRecord requireAggregate(String productKey, String meterKey) {
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(meterKey, "meterKey");
        AuthenticatedPrincipal principal = currentPrincipal.require();
        UUID tenantId = principal.tenantId().orElseThrow(() -> new AuthorizationDeniedException(
                "Usage aggregate read requires a tenant-bound authenticated identity"
        ));
        if (!principal.hasRole(PlatformRole.DEVELOPER)
                && !principal.hasRole(PlatformRole.TENANT_ADMIN)
                && !principal.hasRole(PlatformRole.AUDITOR)
                && !principal.hasRole(PlatformRole.BILLING_OPERATOR)) {
            throw new AuthorizationDeniedException(
                    "Caller lacks a permitted role for usage aggregate read"
            );
        }
        return usageAggregateRepository
                .findByTenantProductKeyAndMeterKey(tenantId, productKey, meterKey)
                .orElseThrow(() -> new UsageAggregateNotFoundException(
                        "No usage aggregate for productKey=" + productKey + " meterKey=" + meterKey
                ));
    }
}
