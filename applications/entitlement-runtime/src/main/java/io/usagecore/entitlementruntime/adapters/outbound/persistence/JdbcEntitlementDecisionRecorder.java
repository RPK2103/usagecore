package io.usagecore.entitlementruntime.adapters.outbound.persistence;

import io.usagecore.entitlementruntime.application.entitlement.EntitlementDecisionRecord;
import io.usagecore.entitlementruntime.application.entitlement.EntitlementDecisionRecorder;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEntitlementDecisionRecorder implements EntitlementDecisionRecorder {

    private static final String INSERT = """
            INSERT INTO entitlement_decision (
                decision_id,
                tenant_id,
                principal_id,
                contract_id,
                contract_version_id,
                contract_version_number,
                product_key,
                feature_key,
                requested_units,
                decision,
                reason,
                configured_limit,
                evaluated_at,
                correlation_id,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcEntitlementDecisionRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(EntitlementDecisionRecord record) {
        jdbcTemplate.update(
                INSERT,
                record.decisionId(),
                record.tenantId(),
                record.principalId(),
                record.contractId(),
                record.contractVersionId(),
                record.contractVersionNumber(),
                record.productKey(),
                record.featureKey(),
                record.requestedUnits(),
                record.decision().name(),
                record.reason(),
                record.configuredLimit(),
                Timestamp.from(record.evaluatedAt()),
                record.correlationId(),
                Timestamp.from(record.createdAt())
        );
    }
}
