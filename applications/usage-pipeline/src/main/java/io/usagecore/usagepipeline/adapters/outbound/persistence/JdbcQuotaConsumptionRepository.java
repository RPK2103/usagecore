package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.quota.QuotaConsumptionRecord;
import io.usagecore.usagepipeline.application.quota.QuotaConsumptionRepository;
import io.usagecore.usagepipeline.application.quota.QuotaDecision;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuotaConsumptionRepository implements QuotaConsumptionRepository {

    private static final RowMapper<QuotaConsumptionRecord> ROW_MAPPER = (rs, rowNum) -> new QuotaConsumptionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("principal_id"),
            rs.getString("product_key"),
            rs.getString("meter_key"),
            rs.getObject("meter_definition_id", UUID.class),
            rs.getString("feature_key"),
            rs.getLong("quantity"),
            rs.getLong("contribution"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getTimestamp("window_start") == null ? null : rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end") == null ? null : rs.getTimestamp("window_end").toInstant(),
            rs.getString("idempotency_key"),
            rs.getString("correlation_id"),
            QuotaDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            (Long) rs.getObject("configured_limit"),
            (Long) rs.getObject("consumed_after"),
            (Long) rs.getObject("remaining_after"),
            rs.getObject("contract_version_id", UUID.class),
            (Integer) rs.getObject("contract_version_number"),
            rs.getTimestamp("decided_at").toInstant()
    );

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO quota_consumption (
                id, event_id, tenant_id, principal_id, product_key, meter_key, meter_definition_id,
                feature_key, quantity, contribution, occurred_at, window_start, window_end,
                idempotency_key, correlation_id, decision, reason, configured_limit,
                consumed_after, remaining_after, contract_version_id, contract_version_number, decided_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private static final String FIND_BY_TENANT_IDEMPOTENCY = """
            SELECT id, event_id, tenant_id, principal_id, product_key, meter_key, meter_definition_id,
                   feature_key, quantity, contribution, occurred_at, window_start, window_end,
                   idempotency_key, correlation_id, decision, reason, configured_limit,
                   consumed_after, remaining_after, contract_version_id, contract_version_number, decided_at
            FROM quota_consumption
            WHERE tenant_id = ? AND idempotency_key = ?
            """;

    private static final String COUNT_BY_TENANT_IDEMPOTENCY = """
            SELECT COUNT(*) FROM quota_consumption
            WHERE tenant_id = ? AND idempotency_key = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcQuotaConsumptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void acquireIdempotencyLock(UUID tenantId, String idempotencyKey) {
        // Session-independent transaction lock; released on commit/rollback.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                rs -> null,
                tenantId.toString(),
                idempotencyKey
        );
    }

    @Override
    public Optional<UUID> insertIfAbsent(QuotaConsumptionRecord record) {
        List<UUID> ids = jdbcTemplate.query(
                INSERT_IF_ABSENT,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                record.id(),
                record.eventId(),
                record.tenantId(),
                record.principalId(),
                record.productKey(),
                record.meterKey(),
                record.meterDefinitionId(),
                record.featureKey(),
                record.quantity(),
                record.contribution(),
                Timestamp.from(record.occurredAt()),
                record.windowStart() == null ? null : Timestamp.from(record.windowStart()),
                record.windowEnd() == null ? null : Timestamp.from(record.windowEnd()),
                record.idempotencyKey(),
                record.correlationId(),
                record.decision().name(),
                record.reason(),
                record.configuredLimit(),
                record.consumedAfter(),
                record.remainingAfter(),
                record.contractVersionId(),
                record.contractVersionNumber(),
                Timestamp.from(record.decidedAt())
        );
        return ids.stream().findFirst();
    }

    @Override
    public Optional<QuotaConsumptionRecord> findByTenantAndIdempotencyKey(UUID tenantId, String idempotencyKey) {
        List<QuotaConsumptionRecord> rows = jdbcTemplate.query(
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
