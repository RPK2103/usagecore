package io.usagecore.usagepipeline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds canonical ledger / derived aggregate / exception / quota rows for Phase 8A reconciliation tests.
 * Test-only deliberate corruption is allowed here — production reconciliation never mutates these tables.
 */
public final class ReconciliationFixtureSeeder {

    private final JdbcTemplate jdbc;
    private final MeterDefinitionFixtureSeeder meters;
    private final CommercialPeriodFixtureSeeder periods;
    private final QuotaCommercialFixtureSeeder quota;

    public ReconciliationFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.meters = new MeterDefinitionFixtureSeeder(jdbc);
        this.periods = new CommercialPeriodFixtureSeeder(jdbc);
        this.quota = new QuotaCommercialFixtureSeeder(jdbc);
    }

    public MeterDefinitionFixtureSeeder meters() {
        return meters;
    }

    public CommercialPeriodFixtureSeeder periods() {
        return periods;
    }

    public QuotaCommercialFixtureSeeder quota() {
        return quota;
    }

    public void clearUsageEvidence() {
        jdbc.update("DELETE FROM usage_adjustment");
        jdbc.update("DELETE FROM reconciliation_item");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM commercial_usage_exception");
        jdbc.update("DELETE FROM commercial_period_transition");
        jdbc.update("DELETE FROM commercial_period");
        jdbc.update("DELETE FROM quota_consumption");
        jdbc.update("DELETE FROM quota_state");
        jdbc.update("DELETE FROM usage_window_aggregate");
        jdbc.update("DELETE FROM usage_aggregate");
        jdbc.update("DELETE FROM usage_ledger");
        jdbc.update("DELETE FROM processed_event");
    }

    public void insertLedgerEvent(
            UUID tenantId,
            String productKey,
            String meterKey,
            long quantity,
            Instant occurredAt,
            UUID eventId
    ) {
        jdbc.update(
                """
                INSERT INTO usage_ledger (
                    id, event_id, tenant_id, product_key, meter_key, quantity,
                    occurred_at, idempotency_key, correlation_id, principal_id, recorded_at, is_late
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                """,
                UUID.randomUUID(),
                eventId,
                tenantId,
                productKey,
                meterKey,
                quantity,
                Timestamp.from(occurredAt),
                "idem-" + eventId,
                "corr-" + eventId,
                "test-principal",
                Timestamp.from(Instant.parse("2026-08-15T00:00:00Z"))
        );
    }

    public void insertWindowAggregate(
            UUID tenantId,
            UUID productId,
            UUID meterDefinitionId,
            String meterKey,
            String aggregationType,
            Instant windowStart,
            Instant windowEnd,
            long aggregateValue,
            long eventCount
    ) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO usage_window_aggregate (
                    id, tenant_id, product_id, meter_definition_id, meter_key,
                    aggregation_type, window_start, window_end,
                    aggregate_value, event_count, first_event_at, last_event_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                productId,
                meterDefinitionId,
                meterKey,
                aggregationType,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                aggregateValue,
                eventCount,
                Timestamp.from(windowStart),
                Timestamp.from(windowStart),
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public void updateWindowAggregateValue(UUID tenantId, UUID meterDefinitionId, long newValue) {
        jdbc.update(
                """
                UPDATE usage_window_aggregate
                SET aggregate_value = ?
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                newValue,
                tenantId,
                meterDefinitionId
        );
    }

    public void deleteWindowAggregates(UUID tenantId, UUID meterDefinitionId) {
        jdbc.update(
                "DELETE FROM usage_window_aggregate WHERE tenant_id = ? AND meter_definition_id = ?",
                tenantId,
                meterDefinitionId
        );
    }

    public Long windowAggregateValue(UUID tenantId, UUID meterDefinitionId) {
        return jdbc.query(
                """
                SELECT aggregate_value FROM usage_window_aggregate
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                rs -> rs.next() ? rs.getLong("aggregate_value") : null,
                tenantId,
                meterDefinitionId
        );
    }

    public long windowAggregateCount(UUID tenantId, UUID meterDefinitionId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM usage_window_aggregate
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                Long.class,
                tenantId,
                meterDefinitionId
        );
        return count == null ? 0L : count;
    }

    public UUID insertException(
            UUID eventId,
            UUID tenantId,
            UUID productId,
            UUID meterDefinitionId,
            UUID commercialPeriodId,
            String reason,
            Instant occurredAt
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO commercial_usage_exception (
                    id, event_id, tenant_id, product_id, meter_definition_id,
                    commercial_period_id, reason, occurred_at, recorded_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                eventId,
                tenantId,
                productId,
                meterDefinitionId,
                commercialPeriodId,
                reason,
                Timestamp.from(occurredAt),
                Timestamp.from(Instant.parse("2026-09-02T00:00:00Z")),
                "corr-exception"
        );
        return id;
    }

    public void insertLifetimeAggregate(
            UUID tenantId,
            UUID productId,
            UUID meterDefinitionId,
            String meterKey,
            String aggregationType,
            long aggregateValue,
            long eventCount
    ) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO usage_aggregate (
                    id, tenant_id, product_id, meter_definition_id, meter_key,
                    aggregation_type, aggregate_value, event_count, last_event_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                productId,
                meterDefinitionId,
                meterKey,
                aggregationType,
                aggregateValue,
                eventCount,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public Long lifetimeAggregateValue(UUID tenantId, UUID meterDefinitionId) {
        return jdbc.query(
                """
                SELECT aggregate_value FROM usage_aggregate
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                rs -> rs.next() ? rs.getLong("aggregate_value") : null,
                tenantId,
                meterDefinitionId
        );
    }

    public Long lifetimeEventCount(UUID tenantId, UUID meterDefinitionId) {
        return jdbc.query(
                """
                SELECT event_count FROM usage_aggregate
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                rs -> rs.next() ? rs.getLong("event_count") : null,
                tenantId,
                meterDefinitionId
        );
    }

    public Long windowEventCount(UUID tenantId, UUID meterDefinitionId) {
        return jdbc.query(
                """
                SELECT event_count FROM usage_window_aggregate
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                rs -> rs.next() ? rs.getLong("event_count") : null,
                tenantId,
                meterDefinitionId
        );
    }

    public long adjustmentCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM usage_adjustment", Long.class);
        return count == null ? 0L : count;
    }

    public long ledgerCountForEvent(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    public Long ledgerQuantity(UUID eventId) {
        return jdbc.query(
                "SELECT quantity FROM usage_ledger WHERE event_id = ?",
                rs -> rs.next() ? rs.getLong("quantity") : null,
                eventId
        );
    }

    public String exceptionReason(UUID exceptionId) {
        return jdbc.queryForObject(
                "SELECT reason FROM commercial_usage_exception WHERE id = ?",
                String.class,
                exceptionId
        );
    }

    public long exceptionCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM commercial_usage_exception", Long.class);
        return count == null ? 0L : count;
    }

    public void insertFailedRun(UUID runId, UUID tenantId, UUID productId, UUID periodId) {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    id, tenant_id, product_id, commercial_period_id, status, result,
                    started_at, completed_at, started_by,
                    canonical_event_count, quarantined_event_count,
                    matched_meter_count, mismatched_meter_count,
                    correlation_id, failure_reason
                ) VALUES (?, ?, ?, ?, 'FAILED', NULL, ?, ?, 'blocker', NULL, NULL, NULL, NULL, NULL, 'forced failure')
                """,
                runId,
                tenantId,
                productId,
                periodId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1))
        );
    }

    public void insertQuotaState(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd,
            long configuredLimit,
            long consumed
    ) {
        jdbc.update(
                """
                INSERT INTO quota_state (
                    id, tenant_id, meter_definition_id, window_start, window_end,
                    configured_limit, consumed_quantity, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                meterDefinitionId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                configuredLimit,
                consumed,
                Timestamp.from(Instant.parse("2026-08-15T00:00:00Z"))
        );
    }

    public Long quotaConsumed(UUID tenantId, UUID meterDefinitionId) {
        return jdbc.query(
                """
                SELECT consumed_quantity FROM quota_state
                WHERE tenant_id = ? AND meter_definition_id = ?
                """,
                rs -> rs.next() ? rs.getLong("consumed_quantity") : null,
                tenantId,
                meterDefinitionId
        );
    }

    public void insertCompletedRun(UUID runId, UUID tenantId, UUID productId, UUID periodId) {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    id, tenant_id, product_id, commercial_period_id, status, result,
                    started_at, completed_at, started_by,
                    canonical_event_count, quarantined_event_count,
                    matched_meter_count, mismatched_meter_count,
                    correlation_id, failure_reason
                ) VALUES (?, ?, ?, ?, 'COMPLETED', 'MATCH', ?, ?, 'seeder', 0, 0, 0, 0, NULL, NULL)
                """,
                runId,
                tenantId,
                productId,
                periodId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1))
        );
    }

    public void insertRunningRun(UUID runId, UUID tenantId, UUID productId, UUID periodId) {
        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    id, tenant_id, product_id, commercial_period_id, status, result,
                    started_at, completed_at, started_by,
                    canonical_event_count, quarantined_event_count,
                    matched_meter_count, mismatched_meter_count,
                    correlation_id, failure_reason
                ) VALUES (?, ?, ?, ?, 'RUNNING', NULL, ?, NULL, 'blocker', NULL, NULL, NULL, NULL, NULL, NULL)
                """,
                runId,
                tenantId,
                productId,
                periodId,
                Timestamp.from(Instant.parse("2026-09-02T12:00:00Z"))
        );
    }

    public String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE id = ?", String.class, runId);
    }

    public long runCountForPeriod(UUID periodId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE commercial_period_id = ?",
                Long.class,
                periodId
        );
        return count == null ? 0L : count;
    }

    public long runningCountForPeriod(UUID periodId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM reconciliation_run
                WHERE commercial_period_id = ? AND status = 'RUNNING'
                """,
                Long.class,
                periodId
        );
        return count == null ? 0L : count;
    }
}
