package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.usage.ProcessedEventRecord;
import io.usagecore.usagepipeline.application.usage.ProcessedEventRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedEventRepository implements ProcessedEventRepository {

    private static final String TRY_CLAIM = """
            INSERT INTO processed_event (
                event_id, event_type, event_version, tenant_id,
                consumer_name, processed_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            RETURNING event_id
            """;

    private static final String COUNT_BY_EVENT_ID = """
            SELECT COUNT(*) FROM processed_event WHERE event_id = ?
            """;

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM processed_event";

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryClaim(ProcessedEventRecord record) {
        List<UUID> claimed = jdbcTemplate.query(
                TRY_CLAIM,
                (rs, rowNum) -> rs.getObject("event_id", UUID.class),
                record.eventId(),
                record.eventType(),
                record.eventVersion(),
                record.tenantId(),
                record.consumerName(),
                Timestamp.from(record.processedAt()),
                record.correlationId()
        );
        return !claimed.isEmpty();
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
