package io.usagecore.controlplane.support;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;

/**
 * Shared RSA key material and JWT minting for integration tests.
 * Does not depend on a live Keycloak instance.
 */
public final class TestJwtSupport {

    public static final RSAKey RSA_KEY;
    public static final JwtDecoder JWT_DECODER;
    public static final JwtEncoder JWT_ENCODER;

    static {
        try {
            RSA_KEY = new RSAKeyGenerator(2048).keyID("usagecore-test").generate();
            JWT_DECODER = NimbusJwtDecoder.withPublicKey(RSA_KEY.toRSAPublicKey()).build();
            JWT_ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA_KEY)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize test JWT support", ex);
        }
    }

    private TestJwtSupport() {
    }

    public static String bearerToken(String subject, List<String> roles, UUID tenantId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("roles", roles);
        if (tenantId != null) {
            claims.claim("tenant_id", tenantId.toString());
        }
        return JWT_ENCODER.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    public static String platformAdmin() {
        return bearerToken("platform-admin", List.of("PLATFORM_ADMIN"), null);
    }

    public static String contractManager(UUID tenantId) {
        return bearerToken("contract-manager-" + tenantId, List.of("CONTRACT_MANAGER"), tenantId);
    }

    public static String tenantAdmin(UUID tenantId) {
        return bearerToken("tenant-admin-" + tenantId, List.of("TENANT_ADMIN"), tenantId);
    }

    public static String auditor(UUID tenantId) {
        return bearerToken("auditor-" + tenantId, List.of("AUDITOR"), tenantId);
    }

    public static String developer(UUID tenantId) {
        return bearerToken("developer-" + tenantId, List.of("DEVELOPER"), tenantId);
    }

    public static String billingOperator(UUID tenantId) {
        return bearerToken("billing-" + tenantId, List.of("BILLING_OPERATOR"), tenantId);
    }

    public static Map<String, Object> malformedTokenHeader() {
        return Map.of("Authorization", "Bearer not-a-jwt");
    }

    public static RSAPublicKey publicKey() {
        try {
            return RSA_KEY.toRSAPublicKey();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static RSAPrivateKey privateKey() {
        try {
            return RSA_KEY.toRSAPrivateKey();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
