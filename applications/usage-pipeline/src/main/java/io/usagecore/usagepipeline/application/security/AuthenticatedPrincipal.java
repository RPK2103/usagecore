package io.usagecore.usagepipeline.application.security;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticated caller identity derived from validated JWT claims.
 * Tenant authority must never come from request body/URL/header selectors.
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
}
