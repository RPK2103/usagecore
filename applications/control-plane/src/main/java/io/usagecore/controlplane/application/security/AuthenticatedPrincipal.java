package io.usagecore.controlplane.application.security;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticated caller identity derived from validated JWT claims.
 * Application code must not trust URL/body/header tenant selectors as authority.
 */
public record AuthenticatedPrincipal(
        String subject,
        Optional<UUID> tenantId,
        Set<PlatformRole> roles
) {

    public AuthenticatedPrincipal {
        roles = roles == null || roles.isEmpty()
                ? EnumSet.noneOf(PlatformRole.class)
                : EnumSet.copyOf(roles);
    }

    public boolean hasRole(PlatformRole role) {
        return roles.contains(role);
    }

    public boolean isPlatformAdmin() {
        return hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    public boolean isTenantBound() {
        return tenantId.isPresent() && !isPlatformAdmin();
    }
}
