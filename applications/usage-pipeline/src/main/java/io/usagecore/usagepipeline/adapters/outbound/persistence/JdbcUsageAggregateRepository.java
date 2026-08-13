package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRecord;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Derived aggregate persistence. Concurrent updates use PostgreSQL UPSERT —
 * never read-modify-write in Java.
 * <p>
 * BIGINT totals can overflow at extreme volumes; PostgreSQL arithmetic is used
 * for concurrency safety. Do not treat aggregates as the sole source of truth —
 * replay from {@code usage_ledger} if rebuild is required.
 */
@Repository
public class JdbcUsageAggregateRepository implements UsageAggregateRepository {

    private static final RowMapper<UsageAggregateRecord> ROW_MAPPER = (rs, rowNum) -> new UsageAggregateRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getObject("meter_definition_id", UUID.class),
            rs.getString("meter_key"),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            rs.getLong("aggregate_value"),
            rs.getLong("event_count"),
            rs.getTimestamp("last_event_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    /**
     * Initial aggregate_value is the contribution delta (quantity for SUM/MAX, 1 for COUNT).
     * On conflict:
     * SUM/COUNT → add EXCLUDED.aggregate_value
     * MAX → GREATEST(existing, incoming)
     * last_event_at → GREATEST (event-time max, not arrival order)
     */
    private static final String UPSERT = """
            INSERT INTO usage_aggregate (
                id, tenant_id, product_id, meter_definition_id, meter_key,
                aggregation_type, aggregate_value, event_count, last_event_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT (tenant_id, meter_definition_id)
            DO UPDATE SET
                aggregate_value = CASE usage_aggregate.aggregation_type
                    WHEN 'MAX' THEN GREATEST(usage_aggregate.aggregate_value, EXCLUDED.aggregate_value)
                    ELSE usage_aggregate.aggregate_value + EXCLUDED.aggregate_value
                END,
                event_count = usage_aggregate.event_count + 1,
                last_event_at = GREATEST(usage_aggregate.last_event_at, EXCLUDED.last_event_at),
                updated_at = EXCLUDED.updated_at
            """;

    private static final String FIND_BY_TENANT_METER = """
            SELECT id, tenant_id, product_id, meter_definition_id, meter_key,
                   aggregation_type, aggregate_value, event_count, last_event_at, updated_at
            FROM usage_aggregate
            WHERE tenant_id = ? AND meter_definition_id = ?
            """;

    private static final String FIND_BY_TENANT_PRODUCT_METER_KEY = """
            SELECT ua.id, ua.tenant_id, ua.product_id, ua.meter_definition_id, ua.meter_key,
                   ua.aggregation_type, ua.aggregate_value, ua.event_count, ua.last_event_at, ua.updated_at
            FROM usage_aggregate ua
            INNER JOIN product p ON p.id = ua.product_id
            WHERE ua.tenant_id = ?
              AND p.product_key = ?
              AND ua.meter_key = ?
            """;

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM usage_aggregate";

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageAggregateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void applyEvent(
            UUID tenantId,
            ActiveMeterDefinition meter,
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
                contribution,
                Timestamp.from(occurredAt),
                Timestamp.from(updatedAt)
        );
    }

    @Override
    public Optional<UsageAggregateRecord> findByTenantAndMeterDefinition(UUID tenantId, UUID meterDefinitionId) {
        List<UsageAggregateRecord> rows = jdbcTemplate.query(
                FIND_BY_TENANT_METER,
                ROW_MAPPER,
                tenantId,
                meterDefinitionId
        );
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UsageAggregateRecord> findByTenantProductKeyAndMeterKey(
            UUID tenantId,
            String productKey,
            String meterKey
    ) {
        List<UsageAggregateRecord> rows = jdbcTemplate.query(
                FIND_BY_TENANT_PRODUCT_METER_KEY,
                ROW_MAPPER,
                tenantId,
                productKey,
                meterKey
        );
        return rows.stream().findFirst();
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL, Long.class);
        return count == null ? 0L : count;
    }
}
