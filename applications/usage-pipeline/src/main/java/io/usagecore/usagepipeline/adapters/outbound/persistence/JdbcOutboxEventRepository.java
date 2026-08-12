package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.outbox.OutboxEventRecord;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxEventRepository implements OutboxEventRepository {

    private static final RowMapper<OutboxEventRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp published = rs.getTimestamp("published_at");
        return new OutboxEventRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("event_version"),
                rs.getString("topic"),
                rs.getString("partition_key"),
                rs.getString("serialized_envelope"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                published == null ? null : published.toInstant()
        );
    };

    private static final String INSERT_PENDING = """
            INSERT INTO outbox_event (
                id, event_id, event_type, event_version, topic, partition_key,
                serialized_envelope, status, created_at, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            """;

    private static final String CLAIM_PENDING = """
            SELECT id, event_id, event_type, event_version, topic, partition_key,
                   serialized_envelope, status, created_at, published_at
            FROM outbox_event
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = ?
            WHERE id = ? AND status = 'PENDING'
            """;

    private static final String FIND_BY_EVENT_ID = """
            SELECT id, event_id, event_type, event_version, topic, partition_key,
                   serialized_envelope, status, created_at, published_at
            FROM outbox_event
            WHERE event_id = ?
            """;

    private static final String COUNT_BY_STATUS = """
            SELECT COUNT(*) FROM outbox_event WHERE status = ?
            """;

    private static final String COUNT_ALL = """
            SELECT COUNT(*) FROM outbox_event
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertPending(OutboxEventRecord record) {
        jdbcTemplate.update(
                INSERT_PENDING,
                record.id(),
                record.eventId(),
                record.eventType(),
                record.eventVersion(),
                record.topic(),
                record.partitionKey(),
                record.serializedEnvelope(),
                OutboxStatus.PENDING.name(),
                Timestamp.from(record.createdAt())
        );
    }

    @Override
    public List<OutboxEventRecord> claimPending(int batchSize) {
        return jdbcTemplate.query(CLAIM_PENDING, ROW_MAPPER, batchSize);
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        jdbcTemplate.update(MARK_PUBLISHED, Timestamp.from(publishedAt), id);
    }

    @Override
    public Optional<OutboxEventRecord> findByEventId(UUID eventId) {
        List<OutboxEventRecord> rows = jdbcTemplate.query(FIND_BY_EVENT_ID, ROW_MAPPER, eventId);
        return rows.stream().findFirst();
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        Long count = jdbcTemplate.queryForObject(COUNT_BY_STATUS, Long.class, status.name());
        return count == null ? 0L : count;
    }

    @Override
    public long countAll() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL, Long.class);
        return count == null ? 0L : count;
    }
}
