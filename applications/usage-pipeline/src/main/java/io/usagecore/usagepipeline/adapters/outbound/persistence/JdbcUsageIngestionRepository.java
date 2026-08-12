package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.usage.UsageIngestionRecord;
import io.usagecore.usagepipeline.application.usage.UsageIngestionRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUsageIngestionRepository implements UsageIngestionRepository {

    private static final RowMapper<UsageIngestionRecord> ROW_MAPPER = (rs, rowNum) -> new UsageIngestionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("principal_id"),
            rs.getString("product_key"),
            rs.getString("meter_key"),
            rs.getLong("quantity"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("idempotency_key"),
            rs.getString("correlation_id"),
            rs.getTimestamp("accepted_at").toInstant()
    );

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO usage_ingestion (
                id, event_id, tenant_id, principal_id, product_key, meter_key,
                quantity, occurred_at, idempotency_key, correlation_id, accepted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private static final String FIND_BY_TENANT_IDEMPOTENCY = """
            SELECT id, event_id, tenant_id, principal_id, product_key, meter_key,
                   quantity, occurred_at, idempotency_key, correlation_id, accepted_at
            FROM usage_ingestion
            WHERE tenant_id = ? AND idempotency_key = ?
            """;

    private static final String COUNT_BY_TENANT_IDEMPOTENCY = """
            SELECT COUNT(*) FROM usage_ingestion
            WHERE tenant_id = ? AND idempotency_key = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageIngestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> insertIfAbsent(UsageIngestionRecord record) {
        List<UUID> ids = jdbcTemplate.query(
                INSERT_IF_ABSENT,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                record.id(),
                record.eventId(),
                record.tenantId(),
                record.principalId(),
                record.productKey(),
                record.meterKey(),
                record.quantity(),
                Timestamp.from(record.occurredAt()),
                record.idempotencyKey(),
                record.correlationId(),
                Timestamp.from(record.acceptedAt())
        );
        return ids.stream().findFirst();
    }

    @Override
    public Optional<UsageIngestionRecord> findByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
        List<UsageIngestionRecord> rows = jdbcTemplate.query(
                FIND_BY_TENANT_IDEMPOTENCY,
                ROW_MAPPER,
                tenantId,
                idempotencyKey
        );
        return rows.stream().findFirst();
    }

    @Override
    public long countByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
        Long count = jdbcTemplate.queryForObject(
                COUNT_BY_TENANT_IDEMPOTENCY,
                Long.class,
                tenantId,
                idempotencyKey
        );
        return count == null ? 0L : count;
    }
}
