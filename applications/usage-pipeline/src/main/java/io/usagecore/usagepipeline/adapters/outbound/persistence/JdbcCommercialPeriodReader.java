package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.commercial.CommercialPeriodReader;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodStatus;
import io.usagecore.usagepipeline.application.commercial.CommercialPeriodView;
import io.usagecore.usagepipeline.application.quota.CommercialInvariantException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Narrow JDBC read of Control Plane–owned {@code commercial_period} rows.
 * Uses {@code FOR SHARE} so finalization transitions cannot interleave ambiguously
 * with usage aggregate mutation in the same PostgreSQL transaction.
 */
@Repository
public class JdbcCommercialPeriodReader implements CommercialPeriodReader {

    private static final RowMapper<CommercialPeriodView> ROW_MAPPER = (rs, rowNum) -> new CommercialPeriodView(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("product_id"),
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            CommercialPeriodStatus.valueOf(rs.getString("status"))
    );

    private static final String FIND_COVERING_FOR_SHARE = """
            SELECT id, tenant_id, product_id, period_start, period_end, status
            FROM commercial_period
            WHERE tenant_id = ?
              AND product_id = ?
              AND period_start <= ?
              AND period_end > ?
            FOR SHARE
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommercialPeriodReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CommercialPeriodView> findCoveringForShare(
            UUID tenantId,
            UUID productId,
            Instant occurredAt
    ) {
        Timestamp at = Timestamp.from(occurredAt);
        List<CommercialPeriodView> rows = jdbcTemplate.query(
                FIND_COVERING_FOR_SHARE,
                ROW_MAPPER,
                tenantId,
                productId,
                at,
                at
        );
        if (rows.size() > 1) {
            throw new CommercialInvariantException(
                    "Multiple commercial periods matched for the same tenant/product/occurredAt"
            );
        }
        return rows.stream().findFirst();
    }
}
