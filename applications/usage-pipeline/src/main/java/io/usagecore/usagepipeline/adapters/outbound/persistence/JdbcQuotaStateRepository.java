package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.quota.QuotaStateRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuotaStateRepository implements QuotaStateRepository {

    private static final String ENSURE_ROW = """
            INSERT INTO quota_state (
                id, tenant_id, meter_definition_id, window_start, window_end,
                configured_limit, consumed_quantity, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 0, ?)
            ON CONFLICT (tenant_id, meter_definition_id, window_start, window_end) DO NOTHING
            """;

    private static final String TRY_CONSUME = """
            UPDATE quota_state
            SET consumed_quantity = consumed_quantity + ?,
                configured_limit = ?,
                updated_at = ?
            WHERE tenant_id = ?
              AND meter_definition_id = ?
              AND window_start = ?
              AND window_end = ?
              AND consumed_quantity + ? <= ?
            RETURNING consumed_quantity
            """;

    private static final String FIND_CONSUMED = """
            SELECT consumed_quantity
            FROM quota_state
            WHERE tenant_id = ?
              AND meter_definition_id = ?
              AND window_start = ?
              AND window_end = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcQuotaStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Long> tryConsume(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd,
            long configuredLimit,
            long contribution,
            Instant updatedAt
    ) {
        jdbcTemplate.update(
                ENSURE_ROW,
                UUID.randomUUID(),
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                configuredLimit,
                Timestamp.from(updatedAt)
        );
        List<Long> updated = jdbcTemplate.query(
                TRY_CONSUME,
                (rs, rowNum) -> rs.getLong("consumed_quantity"),
                contribution,
                configuredLimit,
                Timestamp.from(updatedAt),
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                contribution,
                configuredLimit
        );
        return updated.stream().findFirst();
    }

    @Override
    public Optional<Long> findConsumed(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd
    ) {
        List<Long> rows = jdbcTemplate.query(
                FIND_CONSUMED,
                (rs, rowNum) -> rs.getLong("consumed_quantity"),
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
        return rows.stream().findFirst();
    }
}
