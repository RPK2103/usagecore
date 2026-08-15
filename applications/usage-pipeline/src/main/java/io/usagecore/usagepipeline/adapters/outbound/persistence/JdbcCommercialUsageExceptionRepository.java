package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRecord;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommercialUsageExceptionRepository implements CommercialUsageExceptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCommercialUsageExceptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> insertIfAbsent(CommercialUsageExceptionRecord record) {
        List<UUID> inserted = jdbcTemplate.query(
                """
                INSERT INTO commercial_usage_exception (
                    id, event_id, tenant_id, product_id, meter_definition_id,
                    commercial_period_id, reason, occurred_at, recorded_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                record.id(),
                record.eventId(),
                record.tenantId(),
                record.productId(),
                record.meterDefinitionId(),
                record.commercialPeriodId(),
                record.reason(),
                Timestamp.from(record.occurredAt()),
                Timestamp.from(record.recordedAt()),
                record.correlationId()
        );
        return inserted.stream().findFirst();
    }

    @Override
    public long countByEventId(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM commercial_usage_exception WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM commercial_usage_exception", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public long countUnresolved() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM commercial_usage_exception e
                WHERE NOT EXISTS (
                    SELECT 1 FROM usage_adjustment a
                    WHERE a.commercial_usage_exception_id = e.id
                )
                """,
                Long.class
        );
        return count == null ? 0L : count;
    }
}
