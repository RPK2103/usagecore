package io.usagecore.entitlementruntime.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds minimal commercial catalogue/contract rows for runtime integration tests.
 */
public final class CommercialFixtureSeeder {

    public static final String PRODUCT_KEY = "datapilot-cloud";
    public static final String FEATURE_KEY = "scheduled_exports";

    private final JdbcTemplate jdbc;

    public CommercialFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureTenant(UUID tenantId, String tenantKey) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO tenant (id, tenant_key, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                tenantId,
                tenantKey,
                tenantKey,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public UUID ensureProductAndFeature() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID productId = UUID.nameUUIDFromBytes("datapilot-cloud".getBytes());
        UUID featureId = UUID.nameUUIDFromBytes("scheduled_exports".getBytes());
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
        jdbc.update(
                """
                INSERT INTO feature (id, product_id, feature_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Scheduled Exports', 'ACTIVE', ?, ?)
                ON CONFLICT (product_id, feature_key) DO NOTHING
                """,
                featureId,
                resolvedProductId,
                FEATURE_KEY,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return resolvedProductId;
    }

    public UUID featureId() {
        return jdbc.queryForObject(
                """
                SELECT f.id FROM feature f
                JOIN product p ON p.id = f.product_id
                WHERE p.product_key = ? AND f.feature_key = ?
                """,
                UUID.class,
                PRODUCT_KEY,
                FEATURE_KEY
        );
    }

    public ActivatedContract seedActivatedEntitlement(
            UUID tenantId,
            String contractKey,
            int versionNumber,
            Instant effectiveFrom,
            Instant effectiveUntil,
            String mode,
            Long limitQuantity
    ) {
        UUID productId = ensureProductAndFeature();
        UUID featureId = featureId();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        UUID contractId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO contract (id, tenant_id, product_id, contract_key, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                contractId,
                tenantId,
                productId,
                contractKey,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        UUID versionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO contract_version (
                    id, contract_id, tenant_id, version_number, source_plan_id, status,
                    effective_from, effective_until, activated_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, NULL, 'ACTIVATED', ?, ?, ?, ?, ?)
                """,
                versionId,
                contractId,
                tenantId,
                versionNumber,
                Timestamp.from(effectiveFrom),
                effectiveUntil == null ? null : Timestamp.from(effectiveUntil),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        UUID entitlementId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO entitlement (
                    id, contract_version_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                entitlementId,
                versionId,
                featureId,
                mode,
                limitQuantity,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        return new ActivatedContract(contractId, versionId, versionNumber);
    }

    public UUID seedPlanWithFeature(UUID productId, String planKey, String mode, Long limitQuantity) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID planId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO plan (id, product_id, plan_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?)
                """,
                planId,
                productId,
                planKey,
                planKey,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO plan_feature (
                    id, plan_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                planId,
                featureId(),
                mode,
                limitQuantity,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return planId;
    }

    public void updatePlanFeatureMode(UUID planId, String mode, Long limitQuantity) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        jdbc.update(
                """
                UPDATE plan_feature
                SET entitlement_mode = ?, limit_quantity = ?, updated_at = ?
                WHERE plan_id = ?
                """,
                mode,
                limitQuantity,
                Timestamp.from(now),
                planId
        );
    }

    public record ActivatedContract(UUID contractId, UUID contractVersionId, int versionNumber) {
    }
}
