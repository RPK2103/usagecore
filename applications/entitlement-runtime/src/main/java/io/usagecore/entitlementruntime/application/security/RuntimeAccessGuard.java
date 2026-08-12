package io.usagecore.entitlementruntime.application.security;

/**
 * Ensures entitlement checks run only for tenant-bound callers with runtime authority.
 * Tenant identity always comes from the validated JWT — never from the request body.
 */
public final class RuntimeAccessGuard {

    private RuntimeAccessGuard() {
    }

    public static void requireEntitlementCheckAuthority(AuthenticatedPrincipal principal) {
        if (principal.tenantId().isEmpty()) {
            throw new AuthorizationDeniedException(
                    "Entitlement checks require a tenant-bound authenticated identity"
            );
        }
        boolean permitted = principal.hasRole(PlatformRole.DEVELOPER)
                || principal.hasRole(PlatformRole.TENANT_ADMIN)
                || principal.hasRole(PlatformRole.CONTRACT_MANAGER);
        if (!permitted) {
            throw new AuthorizationDeniedException(
                    "Caller lacks a permitted role for entitlement checks"
            );
        }
    }
}
