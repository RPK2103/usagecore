package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageWindow;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Event-time window aggregate persistence via PostgreSQL UPSERT — never Java RMW.
 */
@Repository
public class JdbcUsageWindowAggregateRepository implements UsageWindowAggregateRepository {

    private static final RowMapper<UsageWindowAggregateRecord> ROW_MAPPER = (rs, rowNum) -> new UsageWindowAggregateRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getObject("meter_definition_id", UUID.class),
            rs.getString("meter_key"),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getLong("aggregate_value"),
            rs.getLong("event_count"),
            rs.getTimestamp("first_event_at") == null ? null : rs.getTimestamp("first_event_at").toInstant(),
            rs.getTimestamp("last_event_at") == null ? null : rs.getTimestamp("last_event_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private static final String UPSERT = """
            INSERT INTO usage_window_aggregate (
                id, tenant_id, product_id, meter_definition_id, meter_key,
                aggregation_type, window_start, window_end,
                aggregate_value, event_count, first_event_at, last_event_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, meter_definition_id, window_start, window_end)
            DO UPDATE SET
                aggregate_value = CASE usage_window_aggregate.aggregation_type
                    WHEN 'MAX' THEN GREATEST(usage_window_aggregate.aggregate_value, EXCLUDED.aggregate_value)
                    ELSE usage_window_aggregate.aggregate_value + EXCLUDED.aggregate_value
                END,
                event_count = usage_window_aggregate.event_count + 1,
                first_event_at = LEAST(usage_window_aggregate.first_event_at, EXCLUDED.first_event_at),
                last_event_at = GREATEST(usage_window_aggregate.last_event_at, EXCLUDED.last_event_at),
                updated_at = EXCLUDED.updated_at
            """;

    private static final String FIND_BY_TENANT_METER_WINDOW = """
            SELECT id, tenant_id, product_id, meter_definition_id, meter_key,
                   aggregation_type, window_start, window_end,
                   aggregate_value, event_count, first_event_at, last_event_at, updated_at
            FROM usage_window_aggregate
            WHERE tenant_id = ?
              AND meter_definition_id = ?
              AND window_start = ?
              AND window_end = ?
            """;

    private static final String FIND_BY_TENANT_PRODUCT_METER_WINDOW = """
            SELECT uwa.id, uwa.tenant_id, uwa.product_id, uwa.meter_definition_id, uwa.meter_key,
                   uwa.aggregation_type, uwa.window_start, uwa.window_end,
                   uwa.aggregate_value, uwa.event_count, uwa.first_event_at, uwa.last_event_at, uwa.updated_at
            FROM usage_window_aggregate uwa
            INNER JOIN product p ON p.id = uwa.product_id
            WHERE uwa.tenant_id = ?
              AND p.product_key = ?
              AND uwa.meter_key = ?
              AND uwa.window_start = ?
              AND uwa.window_end = ?
            """;

    private static final String FIND_OVERLAPPING = """
            SELECT uwa.id, uwa.tenant_id, uwa.product_id, uwa.meter_definition_id, uwa.meter_key,
                   uwa.aggregation_type, uwa.window_start, uwa.window_end,
                   uwa.aggregate_value, uwa.event_count, uwa.first_event_at, uwa.last_event_at, uwa.updated_at
            FROM usage_window_aggregate uwa
            INNER JOIN product p ON p.id = uwa.product_id
            WHERE uwa.tenant_id = ?
              AND p.product_key = ?
              AND uwa.meter_key = ?
              AND uwa.window_start < ?
              AND uwa.window_end > ?
            ORDER BY uwa.window_start
            """;

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM usage_window_aggregate";

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageWindowAggregateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void applyEvent(
            UUID tenantId,
            ActiveMeterDefinition meter,
            UsageWindow window,
            long quantity,
            Instant occurredAt,
            Instant updatedAt
    ) {
        long contribution = switch (meter.aggregationType()) {
            case SUM, MAX -> quantity;
            case COUNT -> 1L;
        };
        jdbcTemplate.update(
                UPSERT,
                UUID.randomUUID(),
                tenantId,
                meter.productId(),
                meter.meterDefinitionId(),
                meter.meterKey(),
                meter.aggregationType().name(),
                Timestamp.from(window.start()),
                Timestamp.from(window.end()),
                contribution,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt)
        );
    }

    @Override
    public Optional<UsageWindowAggregateRecord> findByTenantMeterAndWindow(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd
    ) {
        List<UsageWindowAggregateRecord> rows = jdbcTemplate.query(
                FIND_BY_TENANT_METER_WINDOW,
                ROW_MAPPER,
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UsageWindowAggregateRecord> findByTenantProductMeterAndWindow(
            UUID tenantId,
            String productKey,
            String meterKey,
            Instant windowStart,
            Instant windowEnd
    ) {
        List<UsageWindowAggregateRecord> rows = jdbcTemplate.query(
                FIND_BY_TENANT_PRODUCT_METER_WINDOW,
                ROW_MAPPER,
                tenantId,
                productKey,
                meterKey,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<UsageWindowAggregateRecord> findByTenantProductMeterOverlapping(
            UUID tenantId,
            String productKey,
            String meterKey,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        return jdbcTemplate.query(
                FIND_OVERLAPPING,
                ROW_MAPPER,
                tenantId,
                productKey,
                meterKey,
                Timestamp.from(toExclusive),
                Timestamp.from(fromInclusive)
        );
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL, Long.class);
        return count == null ? 0L : count;
    }
}
