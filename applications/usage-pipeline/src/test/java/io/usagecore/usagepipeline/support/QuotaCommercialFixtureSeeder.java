package io.usagecore.usagepipeline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds tenants / activated contract entitlements for quota consumption tests.
 * Uses the same product/feature keys as {@link MeterDefinitionFixtureSeeder}.
 */
public final class QuotaCommercialFixtureSeeder {

    private final JdbcTemplate jdbc;
    private final MeterDefinitionFixtureSeeder meters;

    public QuotaCommercialFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.meters = new MeterDefinitionFixtureSeeder(jdbc);
    }

    public MeterDefinitionFixtureSeeder meters() {
        return meters;
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

    public UUID ensureCatalogue() {
        return meters.ensureDataPilotProductAndMeters();
    }

    public ActivatedContract seedActivatedEntitlement(
            UUID tenantId,
            String contractKey,
            int versionNumber,
            Instant effectiveFrom,
            Instant effectiveUntil,
            String featureKey,
            String mode,
            Long limitQuantity
    ) {
        UUID productId = ensureCatalogue();
        UUID featureId = meters.featureId(featureKey);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        UUID contractId = jdbc.query(
                "SELECT id FROM contract WHERE tenant_id = ? AND product_id = ?",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId,
                productId
        );
        if (contractId == null) {
            contractId = UUID.randomUUID();
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
        }

        UUID versionId = jdbc.query(
                "SELECT id FROM contract_version WHERE contract_id = ? AND version_number = ?",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                contractId,
                versionNumber
        );
        if (versionId == null) {
            versionId = UUID.randomUUID();
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
        }

        Integer entitlementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entitlement WHERE contract_version_id = ? AND feature_id = ?",
                Integer.class,
                versionId,
                featureId
        );
        if (entitlementCount == null || entitlementCount == 0) {
            jdbc.update(
                    """
                    INSERT INTO entitlement (
                        id, contract_version_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    versionId,
                    featureId,
                    mode,
                    limitQuantity,
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
        }

        return new ActivatedContract(contractId, versionId, versionNumber);
    }

    public void seedQuotaConsumed(
            UUID tenantId,
            String meterKey,
            Instant windowStart,
            Instant windowEnd,
            long configuredLimit,
            long consumedQuantity
    ) {
        UUID meterDefinitionId = meters.meterDefinitionId(meterKey);
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO quota_state (
                    id, tenant_id, meter_definition_id, window_start, window_end,
                    configured_limit, consumed_quantity, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, meter_definition_id, window_start, window_end)
                DO UPDATE SET consumed_quantity = EXCLUDED.consumed_quantity,
                              configured_limit = EXCLUDED.configured_limit,
                              updated_at = EXCLUDED.updated_at
                """,
                UUID.randomUUID(),
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                configuredLimit,
                consumedQuantity,
                Timestamp.from(now)
        );
    }

    public long quotaConsumed(UUID tenantId, String meterKey, Instant windowStart, Instant windowEnd) {
        UUID meterDefinitionId = meters.meterDefinitionId(meterKey);
        Long value = jdbc.queryForObject(
                """
                SELECT consumed_quantity FROM quota_state
                WHERE tenant_id = ? AND meter_definition_id = ?
                  AND window_start = ? AND window_end = ?
                """,
                Long.class,
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
        return value == null ? 0L : value;
    }

    public record ActivatedContract(UUID contractId, UUID contractVersionId, int versionNumber) {
    }
}
