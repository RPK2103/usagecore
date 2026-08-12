package io.usagecore.usagepipeline.application.security;

/**
 * Ensures usage ingestion runs only for tenant-bound callers with submit authority.
 * Tenant identity always comes from the validated JWT — never from the request body.
 * PLATFORM_ADMIN without tenant context is not treated as a tenant.
 */
public final class UsageAccessGuard {

    private UsageAccessGuard() {
    }

    public static void requireUsageSubmitAuthority(AuthenticatedPrincipal principal) {
        if (principal.tenantId().isEmpty()) {
            throw new AuthorizationDeniedException(
                    "Usage ingestion requires a tenant-bound authenticated identity"
            );
        }
        boolean permitted = principal.hasRole(PlatformRole.DEVELOPER);
        if (!permitted) {
            throw new AuthorizationDeniedException(
                    "Caller lacks a permitted role for usage ingestion"
            );
        }
    }
}
