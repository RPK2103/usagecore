package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationClassification;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationItemRecord;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationItemStatus;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRepository;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationResult;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunRecord;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationRunStatus;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReconciliationRepository implements ReconciliationRepository {

    private static final RowMapper<ReconciliationRunRecord> RUN_MAPPER = (rs, rowNum) -> new ReconciliationRunRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getObject("commercial_period_id", UUID.class),
            ReconciliationRunStatus.valueOf(rs.getString("status")),
            rs.getString("result") == null ? null : ReconciliationResult.valueOf(rs.getString("result")),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getString("started_by"),
            rs.getObject("canonical_event_count") == null ? null : rs.getLong("canonical_event_count"),
            rs.getObject("quarantined_event_count") == null ? null : rs.getLong("quarantined_event_count"),
            rs.getObject("matched_meter_count") == null ? null : rs.getInt("matched_meter_count"),
            rs.getObject("mismatched_meter_count") == null ? null : rs.getInt("mismatched_meter_count"),
            rs.getString("correlation_id"),
            rs.getString("failure_reason")
    );

    private static final RowMapper<ReconciliationItemRecord> ITEM_MAPPER = (rs, rowNum) -> new ReconciliationItemRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("reconciliation_run_id", UUID.class),
            rs.getObject("meter_definition_id", UUID.class),
            rs.getString("meter_key"),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getLong("observed_expected_value"),
            rs.getLong("commercial_expected_value"),
            rs.getObject("actual_value") == null ? null : rs.getLong("actual_value"),
            rs.getObject("difference") == null ? null : rs.getLong("difference"),
            rs.getLong("expected_event_count"),
            rs.getObject("actual_event_count") == null ? null : rs.getLong("actual_event_count"),
            rs.getLong("quarantined_event_count"),
            rs.getLong("observed_event_count"),
            rs.getLong("adjusted_event_count"),
            rs.getLong("unresolved_exception_count"),
            rs.getObject("quota_consumed_value") == null ? null : rs.getLong("quota_consumed_value"),
            ReconciliationItemStatus.valueOf(rs.getString("status")),
            ReconciliationClassification.valueOf(rs.getString("classification"))
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcReconciliationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertRunning(ReconciliationRunRecord run) {
        // Serialize with commercial_period lifecycle UPDATE (FOR SHARE vs row UPDATE).
        jdbcTemplate.queryForList(
                "SELECT id FROM commercial_period WHERE id = ? FOR SHARE",
                UUID.class,
                run.commercialPeriodId()
        );
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation_run (
                    id, tenant_id, product_id, commercial_period_id, status, result,
                    started_at, completed_at, started_by,
                    canonical_event_count, quarantined_event_count,
                    matched_meter_count, mismatched_meter_count,
                    correlation_id, failure_reason
                ) VALUES (?, ?, ?, ?, 'RUNNING', NULL, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, NULL)
                """,
                run.id(),
                run.tenantId(),
                run.productId(),
                run.commercialPeriodId(),
                Timestamp.from(run.startedAt()),
                run.startedBy(),
                run.correlationId()
        );
    }

    @Override
    public void complete(ReconciliationRunRecord run, List<ReconciliationItemRecord> items) {
        int updated = jdbcTemplate.update(
                """
                UPDATE reconciliation_run
                SET status = 'COMPLETED',
                    result = ?,
                    completed_at = ?,
                    canonical_event_count = ?,
                    quarantined_event_count = ?,
                    matched_meter_count = ?,
                    mismatched_meter_count = ?,
                    failure_reason = NULL
                WHERE id = ?
                  AND status = 'RUNNING'
                """,
                run.result().name(),
                Timestamp.from(run.completedAt()),
                run.canonicalEventCount(),
                run.quarantinedEventCount(),
                run.matchedMeterCount(),
                run.mismatchedMeterCount(),
                run.id()
        );
        if (updated != 1) {
            throw new IllegalStateException("Expected exactly one RUNNING reconciliation_run to complete");
        }
        for (ReconciliationItemRecord item : items) {
            jdbcTemplate.update(
                    """
                    INSERT INTO reconciliation_item (
                        id, reconciliation_run_id, meter_definition_id, meter_key, aggregation_type,
                        window_start, window_end,
                        observed_expected_value, commercial_expected_value, actual_value, difference,
                        expected_event_count, actual_event_count,
                        quarantined_event_count, observed_event_count,
                        adjusted_event_count, unresolved_exception_count, quota_consumed_value,
                        status, classification
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    item.id(),
                    item.reconciliationRunId(),
                    item.meterDefinitionId(),
                    item.meterKey(),
                    item.aggregationType().name(),
                    Timestamp.from(item.windowStart()),
                    Timestamp.from(item.windowEnd()),
                    item.observedExpectedValue(),
                    item.commercialExpectedValue(),
                    item.actualValue(),
                    item.difference(),
                    item.expectedEventCount(),
                    item.actualEventCount(),
                    item.quarantinedEventCount(),
                    item.observedEventCount(),
                    item.adjustedEventCount(),
                    item.unresolvedExceptionCount(),
                    item.quotaConsumedValue(),
                    item.status().name(),
                    item.classification().name()
            );
        }
    }

    @Override
    public void markFailed(UUID runId, Instant completedAt, String failureReason) {
        jdbcTemplate.update(
                """
                UPDATE reconciliation_run
                SET status = 'FAILED',
                    completed_at = ?,
                    failure_reason = ?,
                    result = NULL
                WHERE id = ?
                  AND status = 'RUNNING'
                """,
                Timestamp.from(completedAt),
                failureReason,
                runId
        );
    }

    @Override
    public Optional<ReconciliationRunRecord> findRunById(UUID runId) {
        List<ReconciliationRunRecord> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, product_id, commercial_period_id, status, result,
                       started_at, completed_at, started_by,
                       canonical_event_count, quarantined_event_count,
                       matched_meter_count, mismatched_meter_count,
                       correlation_id, failure_reason
                FROM reconciliation_run
                WHERE id = ?
                """,
                RUN_MAPPER,
                runId
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ReconciliationRunRecord> findRunByIdAndTenantId(UUID runId, UUID tenantId) {
        List<ReconciliationRunRecord> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, product_id, commercial_period_id, status, result,
                       started_at, completed_at, started_by,
                       canonical_event_count, quarantined_event_count,
                       matched_meter_count, mismatched_meter_count,
                       correlation_id, failure_reason
                FROM reconciliation_run
                WHERE id = ? AND tenant_id = ?
                """,
                RUN_MAPPER,
                runId,
                tenantId
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ReconciliationRunRecord> findRunByIdForUpdate(UUID runId) {
        List<ReconciliationRunRecord> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, product_id, commercial_period_id, status, result,
                       started_at, completed_at, started_by,
                       canonical_event_count, quarantined_event_count,
                       matched_meter_count, mismatched_meter_count,
                       correlation_id, failure_reason
                FROM reconciliation_run
                WHERE id = ?
                FOR UPDATE
                """,
                RUN_MAPPER,
                runId
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<ReconciliationItemRecord> findItemsByRunId(UUID runId) {
        return jdbcTemplate.query(
                """
                SELECT id, reconciliation_run_id, meter_definition_id, meter_key, aggregation_type,
                       window_start, window_end,
                       observed_expected_value, commercial_expected_value, actual_value, difference,
                       expected_event_count, actual_event_count,
                       quarantined_event_count, observed_event_count,
                       adjusted_event_count, unresolved_exception_count, quota_consumed_value,
                       status, classification
                FROM reconciliation_item
                WHERE reconciliation_run_id = ?
                ORDER BY meter_key, window_start, window_end
                """,
                ITEM_MAPPER,
                runId
        );
    }

    @Override
    public boolean existsRunningForPeriod(UUID commercialPeriodId) {
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
