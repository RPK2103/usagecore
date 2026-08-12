package io.usagecore.controlplane.application.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Central tenant-isolation checks for tenant-owned commercial resources.
 * Controllers must not implement ad-hoc cross-tenant comparisons.
 */
@Component
public class TenantAccessGuard {

    private final CurrentPrincipal currentPrincipal;
    private final SecurityAuditRecorder securityAuditRecorder;
    private final CorrelationIdAccessor correlationIdAccessor;

    public TenantAccessGuard(
            CurrentPrincipal currentPrincipal,
            SecurityAuditRecorder securityAuditRecorder,
            CorrelationIdAccessor correlationIdAccessor
    ) {
        this.currentPrincipal = currentPrincipal;
        this.securityAuditRecorder = securityAuditRecorder;
        this.correlationIdAccessor = correlationIdAccessor;
    }

    /**
     * Ensures the caller may operate on a resource owned by {@code resourceTenantId}.
     * PLATFORM_ADMIN may access any tenant. Tenant-bound callers must match JWT {@code tenant_id}.
     */
    public void assertCanAccessTenant(UUID resourceTenantId, String action, String resourceType, String resourceId) {
        Objects.requireNonNull(resourceTenantId, "resourceTenantId");
        AuthenticatedPrincipal principal = currentPrincipal.require();
        if (principal.isPlatformAdmin()) {
            return;
        }
        Optional<UUID> callerTenant = principal.tenantId();
        if (callerTenant.isEmpty()) {
            denyInsufficientTenantBinding(principal, action, resourceType, resourceId);
        }
        if (!callerTenant.get().equals(resourceTenantId)) {
            denyCrossTenant(principal, action, resourceType, resourceId, resourceTenantId);
        }
    }

    /**
     * Resolves which tenant a write targets. Platform admins may use an explicit resource tenant id;
     * tenant-bound callers must match their authenticated tenant and cannot select another.
     */
    public UUID resolveWritableTenantId(UUID requestedTenantId, String action) {
        Objects.requireNonNull(requestedTenantId, "requestedTenantId");
        AuthenticatedPrincipal principal = currentPrincipal.require();
        if (principal.isPlatformAdmin()) {
            return requestedTenantId;
        }
        Optional<UUID> callerTenant = principal.tenantId();
        if (callerTenant.isEmpty()) {
            denyInsufficientTenantBinding(principal, action, "Tenant", requestedTenantId.toString());
        }
        if (!callerTenant.get().equals(requestedTenantId)) {
            denyCrossTenant(principal, action, "Tenant", requestedTenantId.toString(), requestedTenantId);
        }
        return callerTenant.get();
    }

    public void assertHasAnyRole(String action, String resourceType, String resourceId, PlatformRole... roles) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        for (PlatformRole role : roles) {
            if (principal.hasRole(role)) {
                return;
            }
        }
        recordAndThrow(
                SecurityAuditEventType.INSUFFICIENT_ROLE,
                principal,
                action,
                resourceType,
                resourceId,
                "Required role missing for action"
        );
    }

    private void denyCrossTenant(
            AuthenticatedPrincipal principal,
            String action,
            String resourceType,
            String resourceId,
            UUID resourceTenantId
    ) {
        recordAndThrow(
                SecurityAuditEventType.CROSS_TENANT_ACCESS_DENIED,
                principal,
                action,
                resourceType,
                resourceId,
                "Authenticated tenant cannot access resource tenant " + resourceTenantId
        );
    }

    private void denyInsufficientTenantBinding(
            AuthenticatedPrincipal principal,
            String action,
            String resourceType,
            String resourceId
    ) {
        recordAndThrow(
                SecurityAuditEventType.INSUFFICIENT_ROLE,
                principal,
                action,
                resourceType,
                resourceId,
                "Tenant-bound identity required"
        );
    }

    private void recordAndThrow(
            SecurityAuditEventType eventType,
            AuthenticatedPrincipal principal,
            String action,
            String resourceType,
            String resourceId,
            String detail
    ) {
        securityAuditRecorder.append(new SecurityAuditRecord(
                Instant.now(),
                eventType,
                principal.subject(),
                principal.tenantId().orElse(null),
                action,
                resourceType,
                resourceId,
                correlationIdAccessor.currentCorrelationId(),
                detail
        ));
        throw new AuthorizationDeniedException("Access denied");
    }
}
