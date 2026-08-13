package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.usage.UsageLedgerRecord;
import io.usagecore.usagepipeline.application.usage.UsageLedgerRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUsageLedgerRepository implements UsageLedgerRepository {

    private static final RowMapper<UsageLedgerRecord> ROW_MAPPER = (rs, rowNum) -> new UsageLedgerRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("product_key"),
            rs.getString("meter_key"),
            rs.getLong("quantity"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("idempotency_key"),
            rs.getString("correlation_id"),
            rs.getString("principal_id"),
            rs.getTimestamp("recorded_at").toInstant(),
            rs.getBoolean("is_late")
    );

    private static final String INSERT = """
            INSERT INTO usage_ledger (
                id, event_id, tenant_id, product_key, meter_key, quantity,
                occurred_at, idempotency_key, correlation_id, principal_id, recorded_at, is_late
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_EVENT_ID = """
            SELECT id, event_id, tenant_id, product_key, meter_key, quantity,
                   occurred_at, idempotency_key, correlation_id, principal_id, recorded_at, is_late
            FROM usage_ledger
            WHERE event_id = ?
            """;

    private static final String COUNT_BY_EVENT_ID = """
            SELECT COUNT(*) FROM usage_ledger WHERE event_id = ?
            """;

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM usage_ledger";

    private final JdbcTemplate jdbcTemplate;

    public JdbcUsageLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(UsageLedgerRecord record) {
        jdbcTemplate.update(
                INSERT,
                record.id(),
                record.eventId(),
                record.tenantId(),
                record.productKey(),
                record.meterKey(),
                record.quantity(),
                Timestamp.from(record.occurredAt()),
                record.idempotencyKey(),
                record.correlationId(),
                record.principalId(),
                Timestamp.from(record.recordedAt()),
                record.isLate()
        );
    }

    @Override
    public Optional<UsageLedgerRecord> findByEventId(UUID eventId) {
        List<UsageLedgerRecord> rows = jdbcTemplate.query(FIND_BY_EVENT_ID, ROW_MAPPER, eventId);
        return rows.stream().findFirst();
    }

    @Override
    public long countByEventId(UUID eventId) {
        Long count = jdbcTemplate.queryForObject(COUNT_BY_EVENT_ID, Long.class, eventId);
        return count == null ? 0L : count;
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL, Long.class);
        return count == null ? 0L : count;
    }
}
