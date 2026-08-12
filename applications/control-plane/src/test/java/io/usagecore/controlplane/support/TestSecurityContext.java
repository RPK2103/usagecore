package io.usagecore.controlplane.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Installs a JWT authentication into the SecurityContext for non-HTTP service tests.
 */
public final class TestSecurityContext {

    private TestSecurityContext() {
    }

    public static void asPlatformAdmin() {
        install("platform-admin", List.of("PLATFORM_ADMIN"), null);
    }

    public static void asContractManager(UUID tenantId) {
        install("contract-manager", List.of("CONTRACT_MANAGER"), tenantId);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void install(String subject, List<String> roles, UUID tenantId) {
        Instant now = Instant.now();
        Jwt.Builder jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("roles", roles);
        if (tenantId != null) {
            jwt.claim("tenant_id", tenantId.toString());
        }
        var authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.build(), authorities, subject)
        );
    }
}
