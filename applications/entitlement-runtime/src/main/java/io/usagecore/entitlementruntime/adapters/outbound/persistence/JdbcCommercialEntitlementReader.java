package io.usagecore.entitlementruntime.adapters.outbound.persistence;

import io.usagecore.entitlementruntime.application.entitlement.CommercialEntitlementMatch;
import io.usagecore.entitlementruntime.application.entitlement.CommercialEntitlementReader;
import io.usagecore.entitlementruntime.domain.SnapshotEntitlementMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Purpose-built JDBC read of activated ContractVersion entitlement snapshots.
 * Does not read live plan_feature rows. Does not ORDER BY/LIMIT to hide ambiguity.
 */
@Repository
public class JdbcCommercialEntitlementReader implements CommercialEntitlementReader {

    private static final String FIND_EFFECTIVE = """
            SELECT
                c.id AS contract_id,
                cv.id AS contract_version_id,
                cv.version_number AS contract_version_number,
                e.entitlement_mode AS entitlement_mode,
                e.limit_quantity AS configured_limit
            FROM contract c
            INNER JOIN product p
                ON p.id = c.product_id
            INNER JOIN contract_version cv
                ON cv.contract_id = c.id
               AND cv.tenant_id = c.tenant_id
            INNER JOIN entitlement e
                ON e.contract_version_id = cv.id
            INNER JOIN feature f
                ON f.id = e.feature_id
               AND f.product_id = p.id
            WHERE c.tenant_id = ?
              AND p.product_key = ?
              AND f.feature_key = ?
              AND cv.status = 'ACTIVATED'
              AND cv.effective_from <= ?
              AND (cv.effective_until IS NULL OR cv.effective_until > ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommercialEntitlementReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<CommercialEntitlementMatch> findEffectiveEntitlements(
            UUID tenantId,
            String productKey,
            String featureKey,
            Instant evaluationInstant
    ) {
        Timestamp evaluated = Timestamp.from(evaluationInstant);
        return jdbcTemplate.query(
                FIND_EFFECTIVE,
                (rs, rowNum) -> new CommercialEntitlementMatch(
                        (UUID) rs.getObject("contract_id"),
                        (UUID) rs.getObject("contract_version_id"),
                        rs.getInt("contract_version_number"),
                        SnapshotEntitlementMode.valueOf(rs.getString("entitlement_mode")),
                        (Long) rs.getObject("configured_limit")
                ),
                tenantId,
                productKey,
                featureKey,
                evaluated,
                evaluated
        );
    }
}
