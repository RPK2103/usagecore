package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.reconciliation.ReconciliationEvidenceReader;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.AggregationWindow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReconciliationEvidenceReader implements ReconciliationEvidenceReader {

    private static final RowMapper<PeriodSnapshot> PERIOD_MAPPER = (rs, rowNum) -> new PeriodSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            rs.getString("status")
    );

    private static final RowMapper<MeterSnapshot> METER_MAPPER = (rs, rowNum) -> new MeterSnapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getString("meter_key"),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            AggregationWindow.valueOf(rs.getString("aggregation_window"))
    );

    private static final RowMapper<LedgerEventSnapshot> LEDGER_MAPPER = (rs, rowNum) -> new LedgerEventSnapshot(
            rs.getObject("event_id", UUID.class),
            rs.getString("meter_key"),
            rs.getLong("quantity"),
            rs.getTimestamp("occurred_at").toInstant()
    );

    private static final RowMapper<WindowAggregateSnapshot> WINDOW_MAPPER = (rs, rowNum) -> new WindowAggregateSnapshot(
            rs.getObject("meter_definition_id", UUID.class),
            rs.getString("meter_key"),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getLong("aggregate_value"),
            rs.getLong("event_count")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcReconciliationEvidenceReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PeriodSnapshot> findPeriodById(UUID commercialPeriodId) {
        List<PeriodSnapshot> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, product_id, period_start, period_end, status
                FROM commercial_period
                WHERE id = ?
                """,
                PERIOD_MAPPER,
                commercialPeriodId
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<PeriodSnapshot> findPeriodByIdForShare(UUID commercialPeriodId) {
        List<PeriodSnapshot> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, product_id, period_start, period_end, status
                FROM commercial_period
                WHERE id = ?
                FOR SHARE
                """,
                PERIOD_MAPPER,
                commercialPeriodId
        );
        return rows.stream().findFirst();
    }

    @Override
    public String requireProductKey(UUID productId) {
        String key = jdbcTemplate.queryForObject(
                "SELECT product_key FROM product WHERE id = ?",
                String.class,
                productId
        );
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Product not found: " + productId);
        }
        return key;
    }

    @Override
    public List<MeterSnapshot> findActiveMetersByProductId(UUID productId) {
        return jdbcTemplate.query(
                """
                SELECT id, product_id, meter_key, aggregation_type, aggregation_window
                FROM meter_definition
                WHERE product_id = ?
                  AND status = 'ACTIVE'
                ORDER BY meter_key
                """,
                METER_MAPPER,
                productId
        );
    }

    @Override
    public List<LedgerEventSnapshot> findLedgerEvents(
            UUID tenantId,
            String productKey,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    ) {
        return jdbcTemplate.query(
                """
                SELECT event_id, meter_key, quantity, occurred_at
                FROM usage_ledger
                WHERE tenant_id = ?
                  AND product_key = ?
                  AND occurred_at >= ?
                  AND occurred_at < ?
                ORDER BY occurred_at, event_id
                """,
                LEDGER_MAPPER,
                tenantId,
                productKey,
                Timestamp.from(periodStartInclusive),
                Timestamp.from(periodEndExclusive)
        );
    }

    @Override
    public Set<UUID> findQuarantinedEventIds(UUID commercialPeriodId) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT event_id
                FROM commercial_usage_exception
                WHERE commercial_period_id = ?
                """,
                (rs, rowNum) -> rs.getObject("event_id", UUID.class),
                commercialPeriodId
        );
        return new HashSet<>(ids);
    }

    @Override
    public List<WindowAggregateSnapshot> findWindowAggregatesOverlapping(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    ) {
        return jdbcTemplate.query(
                """
                SELECT meter_definition_id, meter_key, aggregation_type,
                       window_start, window_end, aggregate_value, event_count
                FROM usage_window_aggregate
                WHERE tenant_id = ?
                  AND meter_definition_id = ?
                  AND window_start < ?
                  AND window_end > ?
                ORDER BY window_start, window_end
                """,
                WINDOW_MAPPER,
                tenantId,
                meterDefinitionId,
                Timestamp.from(periodEndExclusive),
                Timestamp.from(periodStartInclusive)
        );
    }

    @Override
    public Optional<Long> findQuotaConsumed(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd
    ) {
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT consumed_quantity
                FROM quota_state
                WHERE tenant_id = ?
                  AND meter_definition_id = ?
                  AND window_start = ?
                  AND window_end = ?
                """,
                (rs, rowNum) -> rs.getLong("consumed_quantity"),
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
        return rows.stream().findFirst();
    }
}
