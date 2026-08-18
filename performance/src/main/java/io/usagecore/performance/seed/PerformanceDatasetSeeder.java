package io.usagecore.performance.seed;

import io.usagecore.performance.LabJdbc;
import io.usagecore.performance.PerformanceSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Deterministic performance-only fixture. Aligns tenant id with the local Keycloak
 * Acme placeholder so JWT tenant isolation stays intact.
 * <p>
 * This is not a production bootstrap path and does not bypass commercial invariants:
 * activated contract versions, entitlements, meters, and an OPEN commercial period
 * are inserted in the same shape as integration-test fixtures.
 */
public final class PerformanceDatasetSeeder {

    private static final Instant CATALOGUE_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2027-01-01T00:00:00Z");

    private PerformanceDatasetSeeder() {
    }

    public static void main(String[] args) throws Exception {
        boolean resetQuota = false;
        for (String arg : args) {
            if ("--reset-quota".equals(arg) || "--contention-quota".equals(arg)) {
                resetQuota = true;
            }
        }
        try (Connection connection = LabJdbc.open()) {
            connection.setAutoCommit(false);
            seed(connection, resetQuota);
            connection.commit();
        }
        System.out.println("Performance dataset ready.");
        System.out.println("  tenantId=" + PerformanceSettings.tenantId());
        System.out.println("  tenantKey=" + PerformanceSettings.TENANT_KEY);
        System.out.println("  productKey=" + PerformanceSettings.productKey());
        System.out.println("  featureKey=" + PerformanceSettings.featureKey());
        System.out.println("  eventsMeter=" + PerformanceSettings.eventsMeterKey());
        System.out.println("  consumeMeter=" + PerformanceSettings.consumeMeterKey());
        System.out.println("  quotaLimit=" + PerformanceSettings.quotaLimit());
        System.out.println("  fillerTenants=" + PerformanceSettings.fillerTenants());
    }

    static void seed(Connection connection, boolean resetQuota) throws SQLException {
        UUID tenantId = PerformanceSettings.tenantId();
        ensureTenant(connection, tenantId, PerformanceSettings.TENANT_KEY, "Acme Corp");
        UUID productId = ensureProductAndCatalogue(connection);
        UUID apiFeatureId = featureId(connection, productId, PerformanceSettings.FEATURE_API_ACCESS);
        UUID exportFeatureId = featureId(connection, productId, PerformanceSettings.FEATURE_SCHEDULED_EXPORTS);
        UUID contentionFeatureId = featureId(connection, productId, PerformanceSettings.FEATURE_QUOTA_CONTENTION);
        ensureMeter(
                connection,
                productId,
                apiFeatureId,
                PerformanceSettings.METER_API_REQUESTS,
                "API Requests",
                "SUM",
                "MONTHLY"
        );
        ensureMeter(
                connection,
                productId,
                exportFeatureId,
                PerformanceSettings.METER_SCHEDULED_EXPORT,
                "Scheduled Export",
                "COUNT",
                "MONTHLY"
        );
        ensureMeter(
                connection,
                productId,
                contentionFeatureId,
                PerformanceSettings.METER_QUOTA_CONTENTION,
                "Quota Contention",
                "COUNT",
                "MONTHLY"
        );
        UUID contractId = ensureContract(connection, tenantId, productId, "acme-datapilot");
        UUID versionId = ensureActivatedVersion(connection, contractId, tenantId);
        ensureEntitlement(connection, versionId, apiFeatureId, "ENABLED", null);
        ensureEntitlement(
                connection,
                versionId,
                exportFeatureId,
                "LIMITED",
                PerformanceSettings.quotaLimit()
        );
        ensureEntitlement(
                connection,
                versionId,
                contentionFeatureId,
                "LIMITED",
                PerformanceSettings.contentionQuotaLimit()
        );
        ensureOpenPeriod(connection, tenantId, productId);
        seedFillerTenants(connection, productId, apiFeatureId, exportFeatureId, contentionFeatureId);
        if (resetQuota) {
            resetQuotaState(connection, tenantId);
        }
    }

    private static void ensureTenant(Connection connection, UUID tenantId, String tenantKey, String displayName)
            throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT id FROM tenant WHERE tenant_key = ?"
        )) {
            existing.setString(1, tenantKey);
            try (ResultSet rs = existing.executeQuery()) {
                if (rs.next()) {
                    UUID found = rs.getObject("id", UUID.class);
                    if (!found.equals(tenantId)) {
                        throw new SQLException(
                                "tenant_key '" + tenantKey + "' already exists as " + found
                                        + " but performance lab requires " + tenantId
                                        + " (Keycloak Acme placeholder). Reset the local database or choose another key."
                        );
                    }
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO tenant (id, tenant_key, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, tenantId);
            ps.setString(2, tenantKey);
            ps.setString(3, displayName);
            ps.setTimestamp(4, now);
            ps.setTimestamp(5, now);
            ps.executeUpdate();
        }
    }

    private static UUID ensureProductAndCatalogue(Connection connection) throws SQLException {
        UUID productId = UUID.nameUUIDFromBytes(PerformanceSettings.PRODUCT_KEY.getBytes());
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO product (id, product_key, name, status, created_at, updated_at)
                VALUES (?, ?, 'DataPilot Cloud', 'ACTIVE', ?, ?)
                ON CONFLICT (product_key) DO NOTHING
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, productId);
            ps.setString(2, PerformanceSettings.PRODUCT_KEY);
            ps.setTimestamp(3, now);
            ps.setTimestamp(4, now);
            ps.executeUpdate();
        }
        UUID resolved = scalarUuid(connection, "SELECT id FROM product WHERE product_key = ?", PerformanceSettings.PRODUCT_KEY);
        ensureFeature(connection, resolved, PerformanceSettings.FEATURE_API_ACCESS, "API Access");
        ensureFeature(connection, resolved, PerformanceSettings.FEATURE_SCHEDULED_EXPORTS, "Scheduled Exports");
        ensureFeature(connection, resolved, PerformanceSettings.FEATURE_QUOTA_CONTENTION, "Quota Contention");
        return resolved;
    }

    private static void ensureFeature(Connection connection, UUID productId, String featureKey, String name)
            throws SQLException {
        UUID featureId = UUID.nameUUIDFromBytes((PerformanceSettings.PRODUCT_KEY + "|feature|" + featureKey).getBytes());
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO feature (id, product_id, feature_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (product_id, feature_key) DO NOTHING
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, featureId);
            ps.setObject(2, productId);
            ps.setString(3, featureKey);
            ps.setString(4, name);
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.executeUpdate();
        }
    }

    private static UUID featureId(Connection connection, UUID productId, String featureKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM feature WHERE product_id = ? AND feature_key = ?"
        )) {
            ps.setObject(1, productId);
            ps.setString(2, featureKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("feature not found: " + featureKey);
                }
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private static void ensureMeter(
            Connection connection,
            UUID productId,
            UUID featureId,
            String meterKey,
            String displayName,
            String aggregationType,
            String aggregationWindow
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (product_id, meter_key) DO NOTHING
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, UUID.nameUUIDFromBytes((PerformanceSettings.PRODUCT_KEY + "|" + meterKey).getBytes()));
            ps.setObject(2, productId);
            ps.setObject(3, featureId);
            ps.setString(4, meterKey);
            ps.setString(5, displayName);
            ps.setString(6, aggregationType);
            ps.setString(7, aggregationWindow);
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
            ps.executeUpdate();
        }
    }

    private static UUID ensureContract(Connection connection, UUID tenantId, UUID productId, String contractKey)
            throws SQLException {
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id FROM contract WHERE tenant_id = ? AND product_id = ?"
        )) {
            find.setObject(1, tenantId);
            find.setObject(2, productId);
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("id", UUID.class);
                }
            }
        }
        UUID contractId = UUID.nameUUIDFromBytes(("perf-contract|" + tenantId).getBytes());
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO contract (id, tenant_id, product_id, contract_key, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, contractId);
            ps.setObject(2, tenantId);
            ps.setObject(3, productId);
            ps.setString(4, contractKey);
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.executeUpdate();
        }
        return contractId;
    }

    private static UUID ensureActivatedVersion(Connection connection, UUID contractId, UUID tenantId)
            throws SQLException {
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id FROM contract_version WHERE contract_id = ? AND version_number = 1"
        )) {
            find.setObject(1, contractId);
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("id", UUID.class);
                }
            }
        }
        UUID versionId = UUID.nameUUIDFromBytes(("perf-version|" + contractId).getBytes());
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO contract_version (
                    id, contract_id, tenant_id, version_number, source_plan_id, status,
                    effective_from, effective_until, activated_at, created_at, updated_at
                ) VALUES (?, ?, ?, 1, NULL, 'ACTIVATED', ?, NULL, ?, ?, ?)
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, versionId);
            ps.setObject(2, contractId);
            ps.setObject(3, tenantId);
            ps.setTimestamp(4, Timestamp.from(EFFECTIVE_FROM));
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.executeUpdate();
        }
        return versionId;
    }

    private static void ensureEntitlement(
            Connection connection,
            UUID versionId,
            UUID featureId,
            String mode,
            Long limit
    ) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM entitlement WHERE contract_version_id = ? AND feature_id = ?"
        )) {
            count.setObject(1, versionId);
            count.setObject(2, featureId);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO entitlement (
                    id, contract_version_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, versionId);
            ps.setObject(3, featureId);
            ps.setString(4, mode);
            if (limit == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, limit);
            }
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.executeUpdate();
        }
    }

    private static void ensureOpenPeriod(Connection connection, UUID tenantId, UUID productId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO commercial_period (
                    id, tenant_id, product_id, period_start, period_end, status,
                    created_at, updated_at, closing_started_at, reconciling_started_at,
                    finalized_at, finalized_by
                ) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, NULL, NULL, NULL, NULL)
                ON CONFLICT (tenant_id, product_id, period_start, period_end) DO NOTHING
                """
        )) {
            Timestamp now = Timestamp.from(CATALOGUE_TIME);
            ps.setObject(1, UUID.nameUUIDFromBytes(("perf-period|" + tenantId).getBytes()));
            ps.setObject(2, tenantId);
            ps.setObject(3, productId);
            ps.setTimestamp(4, Timestamp.from(PERIOD_START));
            ps.setTimestamp(5, Timestamp.from(PERIOD_END));
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            ps.executeUpdate();
        }
    }

    private static void seedFillerTenants(
            Connection connection,
            UUID productId,
            UUID apiFeatureId,
            UUID exportFeatureId,
            UUID contentionFeatureId
    ) throws SQLException {
        int fillers = PerformanceSettings.fillerTenants();
        for (int i = 0; i < fillers; i++) {
            UUID fillerId = UUID.nameUUIDFromBytes(("perf-filler-tenant-" + i).getBytes());
            String key = "perf-filler-" + i;
            ensureTenant(connection, fillerId, key, key);
            UUID contractId = ensureContract(connection, fillerId, productId, "filler-datapilot");
            UUID versionId = ensureActivatedVersion(connection, contractId, fillerId);
            ensureEntitlement(connection, versionId, apiFeatureId, "ENABLED", null);
            ensureEntitlement(connection, versionId, exportFeatureId, "LIMITED", PerformanceSettings.quotaLimit());
            ensureEntitlement(
                    connection,
                    versionId,
                    contentionFeatureId,
                    "LIMITED",
                    PerformanceSettings.contentionQuotaLimit()
            );
        }
    }

    private static void resetQuotaState(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM quota_state WHERE tenant_id = ?"
        )) {
            ps.setObject(1, tenantId);
            int removed = ps.executeUpdate();
            System.out.println("Reset quota_state rows for tenant: " + removed);
        }
    }

    private static UUID scalarUuid(Connection connection, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("expected row: " + sql);
                }
                return rs.getObject(1, UUID.class);
            }
        }
    }
}
