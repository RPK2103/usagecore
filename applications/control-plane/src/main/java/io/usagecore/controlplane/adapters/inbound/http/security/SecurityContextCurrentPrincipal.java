package io.usagecore.controlplane.adapters.inbound.http.security;

import io.usagecore.controlplane.application.security.AuthenticatedPrincipal;
import io.usagecore.controlplane.application.security.CurrentPrincipal;
import io.usagecore.controlplane.application.security.PlatformRole;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves {@link AuthenticatedPrincipal} from Spring Security's request-bound context.
 */
@Component
public class SecurityContextCurrentPrincipal implements CurrentPrincipal {

    public static final String TENANT_ID_CLAIM = "tenant_id";

    @Override
    public AuthenticatedPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated principal");
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("Expected JWT authentication");
        }
        Jwt jwt = jwtAuth.getToken();
        Set<PlatformRole> roles = jwtAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .map(this::toRole)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PlatformRole.class)));

        Optional<UUID> tenantId = Optional.ofNullable(jwt.getClaimAsString(TENANT_ID_CLAIM))
                .filter(value -> !value.isBlank())
                .map(UUID::fromString);

        return new AuthenticatedPrincipal(jwt.getSubject(), tenantId, roles);
    }

    private Optional<PlatformRole> toRole(String name) {
        try {
            return Optional.of(PlatformRole.valueOf(name));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
