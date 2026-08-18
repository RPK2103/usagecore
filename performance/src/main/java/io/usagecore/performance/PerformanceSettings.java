package io.usagecore.performance;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Configurable local-lab parameters. System properties override defaults.
 * None of these values are production capacity claims.
 */
public final class PerformanceSettings {

    public static final UUID ACME_PLACEHOLDER_TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    public static final String PRODUCT_KEY = "datapilot-cloud";
    public static final String FEATURE_API_ACCESS = "api_access";
    public static final String FEATURE_SCHEDULED_EXPORTS = "scheduled_exports";
    public static final String FEATURE_QUOTA_CONTENTION = "quota_contention";
    public static final String METER_API_REQUESTS = "api_requests";
    public static final String METER_SCHEDULED_EXPORT = "scheduled_export";
    public static final String METER_QUOTA_CONTENTION = "quota_contention";
    public static final String TENANT_KEY = "acme";

    private PerformanceSettings() {
    }

    public static String property(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName(key));
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static int intProperty(String key, int defaultValue) {
        return Integer.parseInt(property(key, Integer.toString(defaultValue)));
    }

    public static double doubleProperty(String key, double defaultValue) {
        return Double.parseDouble(property(key, Double.toString(defaultValue)));
    }

    public static LoadProfile profile() {
        return LoadProfile.from(property("usagecore.perf.profile", "smoke"));
    }

    public static String entitlementBaseUrl() {
        return property("usagecore.perf.baseUrl.entitlement", "http://localhost:8082");
    }

    public static String usageBaseUrl() {
        return property("usagecore.perf.baseUrl.usage", "http://localhost:8083");
    }

    public static String jdbcUrl() {
        return property("usagecore.perf.jdbcUrl", "jdbc:postgresql://localhost:5432/usagecore");
    }

    public static String jdbcUser() {
        return property("usagecore.perf.jdbcUser", "usagecore");
    }

    public static String jdbcPassword() {
        return property("usagecore.perf.jdbcPassword", "usagecore");
    }

    public static String keycloakTokenUrl() {
        return property(
                "usagecore.perf.keycloak.tokenUrl",
                "http://localhost:8081/realms/usagecore/protocol/openid-connect/token"
        );
    }

    public static String keycloakClientId() {
        return property("usagecore.perf.keycloak.clientId", "usagecore-control-plane");
    }

    public static String keycloakUsername() {
        return property("usagecore.perf.keycloak.username", "acme-developer");
    }

    public static String keycloakPassword() {
        return property("usagecore.perf.keycloak.password", "acme-developer");
    }

    public static Optional<String> staticToken() {
        String token = property("usagecore.perf.token", "");
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    public static UUID tenantId() {
        return UUID.fromString(property("usagecore.perf.tenantId", ACME_PLACEHOLDER_TENANT_ID.toString()));
    }

    public static String productKey() {
        return property("usagecore.perf.productKey", PRODUCT_KEY);
    }

    public static String featureKey() {
        return property("usagecore.perf.featureKey", FEATURE_SCHEDULED_EXPORTS);
    }

    public static String eventsMeterKey() {
        return property("usagecore.perf.meterKey.events", METER_API_REQUESTS);
    }

    public static String consumeMeterKey() {
        return property("usagecore.perf.meterKey.consume", METER_SCHEDULED_EXPORT);
    }

    public static int users() {
        return intProperty("usagecore.perf.users", 4);
    }

    public static double requestsPerSecond() {
        return doubleProperty("usagecore.perf.rps", 5.0d);
    }

    public static int durationSeconds() {
        return intProperty("usagecore.perf.durationSeconds", 20);
    }

    public static int rampSeconds() {
        return intProperty("usagecore.perf.rampSeconds", 15);
    }

    public static double burstRps() {
        return doubleProperty("usagecore.perf.burstRps", 30.0d);
    }

    public static int burstSeconds() {
        return intProperty("usagecore.perf.burstSeconds", 8);
    }

    public static int fillerTenants() {
        return intProperty("usagecore.perf.fillerTenants", 50);
    }

    public static long quotaLimit() {
        return Long.parseLong(property("usagecore.perf.quotaLimit", "1000000"));
    }

    public static long contentionQuotaLimit() {
        return Long.parseLong(property("usagecore.perf.contentionQuotaLimit", "5000"));
    }

    public static int drainWaitSeconds() {
        return intProperty("usagecore.perf.drainWaitSeconds", 120);
    }

    public static String runId() {
        return property("usagecore.perf.runId", "local");
    }

    public static Duration duration() {
        return Duration.ofSeconds(durationSeconds());
    }

    public static Duration ramp() {
        return Duration.ofSeconds(rampSeconds());
    }

    private static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
