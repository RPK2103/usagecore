package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.adjustment.AdjustmentType;
import io.usagecore.usagepipeline.application.adjustment.UsageAdjustmentRecord;
import io.usagecore.usagepipeline.application.adjustment.UsageAdjustmentRepository;
import io.usagecore.usagepipeline.application.reconciliation.ReconciliationEvidenceReader;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUsageAdjustmentRepository implements UsageAdjustmentRepository {

    private static final RowMapper<UsageAdjustmentRecord> ROW_MAPPER = (rs, rowNum) -> new UsageAdjustmentRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getObject("meter_definition_id", UUID.class),
            rs.getString("meter_key"),
            rs.getObject("commercial_period_id", UUID.class),
            rs.getObject("commercial_usage_exception_id", UUID.class),
            rs.getObject("source_event_id", UUID.class),
            rs.getObject("reconciliation_run_id", UUID.class),
            AdjustmentType.valueOf(rs.getString("adjustment_type")),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            rs.getLong("quantity"),
            rs.getLong("aggregate_value_contribution"),
            rs.getLong("event_count_contribution"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getString("idempotency_key"),
            rs.getString("reason"),
            rs.getTimestamp("applied_at").toInstant(),
            rs.getString("applied_by"),
            rs.getString("correlation_id")
    );

    private static final RowMapper<ExceptionSnapshot> EXCEPTION_MAPPER = (rs, rowNum) -> new ExceptionSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getObject("meter_definition_id", UUID.class),
            rs.getObject("commercial_period_id", UUID.class),
            rs.getString("reason"),
            rs.getTimestamp("occurred_at").toInstant()
    );

    private static final RowMapper<ReconciliationEvidenceReader.PeriodSnapshot> PERIOD_MAPPER =
            (rs, rowNum) -> new ReconciliationEvidenceReader.PeriodSnapshot(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("product_id", UUID.class),
                    rs.getTimestamp("period_start").toInstant(),
                    rs.getTimestamp("period_end").toInstant(),
                    rs.getString("status")
            );

    private static final String SELECT_COLUMNS = """
            ua.id, ua.tenant_id, ua.product_id, ua.meter_definition_id, md.meter_key,
            ua.commercial_period_id,
            ua.commercial_usage_exception_id, ua.source_event_id, ua.reconciliation_run_id,
            ua.adjustment_type, ua.aggregation_type, ua.quantity, ua.aggregate_value_contribution,
            ua.event_count_contribution, ua.window_start, ua.window_end, ua.idempotency_key, ua.reason,
            ua.applied_at, ua.applied_by, ua.correlation_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ReconciliationEvidenceReader.PeriodSnapshot lockPeriodForUpdate(UUID commercialPeriodId) {
        List<ReconciliationEvidenceReader.PeriodSnapshot> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, product_id, period_start, period_end, status
                FROM commercial_period
                WHERE id = ?
                FOR UPDATE
                """,
                PERIOD_MAPPER,
                commercialPeriodId
        );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Commercial period not found while locking: " + commercialPeriodId);
        }
        return rows.getFirst();
    }

    @Override
    public Optional<UsageAdjustmentRecord> insert(UsageAdjustmentRecord record) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO usage_adjustment (
                    id, tenant_id, product_id, meter_definition_id, commercial_period_id,
                    commercial_usage_exception_id, source_event_id, reconciliation_run_id,
                    adjustment_type, aggregation_type, quantity, aggregate_value_contribution,
                    event_count_contribution, window_start, window_end, idempotency_key, reason,
                    applied_at, applied_by, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                record.id(),
                record.tenantId(),
                record.productId(),
                record.meterDefinitionId(),
                record.commercialPeriodId(),
                record.commercialUsageExceptionId(),
                record.sourceEventId(),
                record.reconciliationRunId(),
                record.adjustmentType().name(),
                record.aggregationType().name(),
                record.quantity(),
                record.aggregateValueContribution(),
                record.eventCountContribution(),
                Timestamp.from(record.windowStart()),
                Timestamp.from(record.windowEnd()),
                record.idempotencyKey(),
                record.reason(),
                Timestamp.from(record.appliedAt()),
                record.appliedBy(),
                record.correlationId()
        );
        return inserted == 1 ? Optional.of(record) : Optional.empty();
    }

    @Override
    public Optional<UsageAdjustmentRecord> findById(UUID adjustmentId) {
        return queryOne("SELECT " + SELECT_COLUMNS + " FROM usage_adjustment ua "
                + "INNER JOIN meter_definition md ON md.id = ua.meter_definition_id WHERE ua.id = ?", adjustmentId);
    }

    @Override
    public Optional<UsageAdjustmentRecord> findByTenantIdAndId(UUID tenantId, UUID adjustmentId) {
        return queryOne(
                "SELECT " + SELECT_COLUMNS + " FROM usage_adjustment ua "
                        + "INNER JOIN meter_definition md ON md.id = ua.meter_definition_id "
                        + "WHERE ua.tenant_id = ? AND ua.id = ?",
                tenantId,
                adjustmentId
        );
    }

    @Override
    public Optional<UsageAdjustmentRecord> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return queryOne(
                "SELECT " + SELECT_COLUMNS + " FROM usage_adjustment ua "
                        + "INNER JOIN meter_definition md ON md.id = ua.meter_definition_id "
                        + "WHERE ua.tenant_id = ? AND ua.idempotency_key = ?",
                tenantId,
                idempotencyKey
        );
    }

    @Override
    public Optional<UsageAdjustmentRecord> findByCommercialUsageExceptionId(UUID exceptionId) {
        return queryOne(
                "SELECT " + SELECT_COLUMNS + " FROM usage_adjustment ua "
                        + "INNER JOIN meter_definition md ON md.id = ua.meter_definition_id "
                        + "WHERE ua.commercial_usage_exception_id = ?",
                exceptionId
        );
    }

    @Override
    public Optional<UsageAdjustmentRecord> findBySourceEventId(UUID sourceEventId) {
        return queryOne(
                "SELECT " + SELECT_COLUMNS + " FROM usage_adjustment ua "
                        + "INNER JOIN meter_definition md ON md.id = ua.meter_definition_id "
                        + "WHERE ua.source_event_id = ?",
                sourceEventId
        );
    }

    @Override
    public Optional<ExceptionSnapshot> lockExceptionForUpdate(UUID exceptionId) {
        List<ExceptionSnapshot> rows = jdbcTemplate.query(
                """
                SELECT id, event_id, tenant_id, product_id, meter_definition_id,
                       commercial_period_id, reason, occurred_at
                FROM commercial_usage_exception
                WHERE id = ?
                FOR UPDATE
                """,
                EXCEPTION_MAPPER,
                exceptionId
        );
        return rows.stream().findFirst();
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_adjustment", Long.class);
        return count == null ? 0L : count;
    }

    private Optional<UsageAdjustmentRecord> queryOne(String sql, Object... args) {
        List<UsageAdjustmentRecord> rows = jdbcTemplate.query(sql, ROW_MAPPER, args);
        return rows.stream().findFirst();
    }
}
