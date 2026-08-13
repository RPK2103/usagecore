package io.usagecore.usagepipeline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds DataPilot Cloud product + features + Phase 6A/6B/6C demo meters for usage-pipeline tests.
 */
public final class MeterDefinitionFixtureSeeder {

    public static final String PRODUCT_KEY = "datapilot-cloud";
    public static final String FEATURE_API_ACCESS = "api_access";
    public static final String FEATURE_SCHEDULED_EXPORT = "scheduled_export_feature";
    public static final String FEATURE_WORKSPACE = "workspace";
    public static final String METER_API_REQUESTS = "api_requests";
    public static final String METER_SCHEDULED_EXPORT = "scheduled_export";
    public static final String METER_WORKSPACE_SIZE = "workspace_size";

    private final JdbcTemplate jdbc;

    public MeterDefinitionFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID ensureDataPilotProductAndMeters() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID productId = UUID.nameUUIDFromBytes(PRODUCT_KEY.getBytes());
        jdbc.update(
                """
                INSERT INTO product (id, product_key, name, status, created_at, updated_at)
                VALUES (?, ?, 'DataPilot Cloud', 'ACTIVE', ?, ?)
                ON CONFLICT (product_key) DO NOTHING
                """,
                productId,
                PRODUCT_KEY,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        UUID resolvedProductId = jdbc.queryForObject(
                "SELECT id FROM product WHERE product_key = ?",
                UUID.class,
                PRODUCT_KEY
        );
        UUID apiFeatureId = ensureFeature(resolvedProductId, FEATURE_API_ACCESS, "API Access", now);
        UUID exportFeatureId = ensureFeature(resolvedProductId, FEATURE_SCHEDULED_EXPORT, "Scheduled Export", now);
        UUID workspaceFeatureId = ensureFeature(resolvedProductId, FEATURE_WORKSPACE, "Workspace", now);
        ensureMeter(resolvedProductId, apiFeatureId, METER_API_REQUESTS, "API Requests", "SUM", "MONTHLY", now);
        ensureMeter(
                resolvedProductId,
                exportFeatureId,
                METER_SCHEDULED_EXPORT,
                "Scheduled Export",
                "COUNT",
                "MONTHLY",
                now
        );
        ensureMeter(
                resolvedProductId,
                workspaceFeatureId,
                METER_WORKSPACE_SIZE,
                "Workspace Size",
                "MAX",
                "MONTHLY",
                now
        );
        return resolvedProductId;
    }

    public void ensureInactiveMeter(String meterKey) {
        UUID productId = ensureDataPilotProductAndMeters();
        UUID featureId = featureId(FEATURE_API_ACCESS);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'SUM', 'MONTHLY', 'INACTIVE', ?, ?)
                ON CONFLICT (product_id, meter_key) DO UPDATE SET status = 'INACTIVE', updated_at = EXCLUDED.updated_at
                """,
                UUID.nameUUIDFromBytes(("inactive-" + meterKey).getBytes()),
                productId,
                featureId,
                meterKey,
                meterKey,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public UUID meterDefinitionId(String meterKey) {
        return jdbc.queryForObject(
                """
                SELECT md.id FROM meter_definition md
                JOIN product p ON p.id = md.product_id
                WHERE p.product_key = ? AND md.meter_key = ?
                """,
                UUID.class,
                PRODUCT_KEY,
                meterKey
        );
    }

    public UUID featureId(String featureKey) {
        return jdbc.queryForObject(
                """
                SELECT f.id FROM feature f
                JOIN product p ON p.id = f.product_id
                WHERE p.product_key = ? AND f.feature_key = ?
                """,
                UUID.class,
                PRODUCT_KEY,
                featureKey
        );
    }

    private UUID ensureFeature(UUID productId, String featureKey, String name, Instant now) {
        UUID featureId = UUID.nameUUIDFromBytes((PRODUCT_KEY + "|feature|" + featureKey).getBytes());
        jdbc.update(
                """
                INSERT INTO feature (id, product_id, feature_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (product_id, feature_key) DO NOTHING
                """,
                featureId,
                productId,
                featureKey,
                name,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return jdbc.queryForObject(
                "SELECT id FROM feature WHERE product_id = ? AND feature_key = ?",
                UUID.class,
                productId,
                featureKey
        );
    }

    private void ensureMeter(
            UUID productId,
            UUID featureId,
            String meterKey,
            String displayName,
            String aggregationType,
            String aggregationWindow,
            Instant now
    ) {
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (product_id, meter_key) DO NOTHING
                """,
                UUID.nameUUIDFromBytes((PRODUCT_KEY + "|" + meterKey).getBytes()),
                productId,
                featureId,
                meterKey,
                displayName,
                aggregationType,
                aggregationWindow,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }
}
