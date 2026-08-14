package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.ActiveReconciliationProbe;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads Usage Pipeline–owned {@code reconciliation_run} via shared PostgreSQL.
 * No compile-time dependency on the usage-pipeline module.
 */
@Component
class JdbcActiveReconciliationProbe implements ActiveReconciliationProbe {

    private final JdbcTemplate jdbcTemplate;

    JdbcActiveReconciliationProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean hasRunningReconciliation(UUID commercialPeriodId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM reconciliation_run
                    WHERE commercial_period_id = ?
                      AND status = 'RUNNING'
                )
                """,
                Boolean.class,
                commercialPeriodId
        );
        return Boolean.TRUE.equals(exists);
    }
}
