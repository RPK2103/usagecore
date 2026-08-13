package io.usagecore.usagepipeline.application.usage;

import io.usagecore.usagepipeline.application.security.AuthenticatedPrincipal;
import io.usagecore.usagepipeline.application.security.AuthorizationDeniedException;
import io.usagecore.usagepipeline.application.security.CurrentPrincipal;
import io.usagecore.usagepipeline.application.security.PlatformRole;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped read of derived event-time window aggregates. Tenant always from JWT.
 */
@Service
public class UsageWindowAggregateQueryService {

    private final CurrentPrincipal currentPrincipal;
    private final UsageWindowAggregateRepository usageWindowAggregateRepository;
    private final UsageWindowResolver usageWindowResolver;
    private final MeterDefinitionLookup meterDefinitionLookup;
    private final Clock clock;

    public UsageWindowAggregateQueryService(
            CurrentPrincipal currentPrincipal,
            UsageWindowAggregateRepository usageWindowAggregateRepository,
            UsageWindowResolver usageWindowResolver,
            MeterDefinitionLookup meterDefinitionLookup,
            Clock clock
    ) {
        this.currentPrincipal = currentPrincipal;
        this.usageWindowAggregateRepository = usageWindowAggregateRepository;
        this.usageWindowResolver = usageWindowResolver;
        this.meterDefinitionLookup = meterDefinitionLookup;
        this.clock = clock;
    }

    /**
     * Returns the event-time window aggregate that contains the current processing clock instant.
     */
    @Transactional(readOnly = true)
    public UsageWindowAggregateRecord requireCurrentWindow(String productKey, String meterKey) {
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(meterKey, "meterKey");
        UUID tenantId = requireAuthorizedTenantId();
        ActiveMeterDefinition meter = meterDefinitionLookup
                .findActiveByProductKeyAndMeterKey(productKey, meterKey)
                .orElseThrow(() -> new UsageAggregateNotFoundException(
                        "No active meter for productKey=" + productKey + " meterKey=" + meterKey
                ));
        UsageWindow window = usageWindowResolver.resolve(clock.instant(), meter.aggregationWindow());
        return usageWindowAggregateRepository
                .findByTenantProductMeterAndWindow(
                        tenantId,
                        productKey,
                        meterKey,
                        window.start(),
                        window.end()
                )
                .orElseThrow(() -> new UsageAggregateNotFoundException(
                        "No usage window aggregate for productKey=" + productKey
                                + " meterKey=" + meterKey
                                + " windowStart=" + window.start()
                ));
    }

    private UUID requireAuthorizedTenantId() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        UUID tenantId = principal.tenantId().orElseThrow(() -> new AuthorizationDeniedException(
                "Usage window aggregate read requires a tenant-bound authenticated identity"
        ));
        if (!principal.hasRole(PlatformRole.DEVELOPER)
                && !principal.hasRole(PlatformRole.TENANT_ADMIN)
                && !principal.hasRole(PlatformRole.AUDITOR)
                && !principal.hasRole(PlatformRole.BILLING_OPERATOR)) {
            throw new AuthorizationDeniedException(
                    "Caller lacks a permitted role for usage window aggregate read"
            );
        }
        return tenantId;
    }
}
