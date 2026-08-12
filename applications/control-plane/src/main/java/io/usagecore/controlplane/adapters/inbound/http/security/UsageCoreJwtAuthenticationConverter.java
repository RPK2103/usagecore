package io.usagecore.controlplane.adapters.inbound.http.security;

import io.usagecore.controlplane.application.security.PlatformRole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Maps IdP-agnostic JWT role claims into Spring authorities.
 * Supports a top-level {@code roles} claim and Keycloak {@code realm_access.roles}.
 */
@Component
public class UsageCoreJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Set<String> roleNames = new HashSet<>();
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof Collection<?> collection) {
            collection.stream().map(Object::toString).forEach(roleNames::add);
        }
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object realmRoles = realmAccess.get("roles");
            if (realmRoles instanceof Collection<?> collection) {
                collection.stream().map(Object::toString).forEach(roleNames::add);
            }
        }

        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String raw : roleNames) {
            String normalized = normalize(raw);
            for (PlatformRole role : PlatformRole.values()) {
                if (role.name().equals(normalized)) {
                    authorities.add(new SimpleGrantedAuthority(role.authority()));
                }
            }
        }
        return authorities;
    }

    private static String normalize(String raw) {
        String value = raw.trim();
        if (value.regionMatches(true, 0, "ROLE_", 0, 5)) {
            value = value.substring(5);
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
