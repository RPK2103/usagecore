package io.usagecore.performance.db;

import io.usagecore.performance.LabJdbc;
import io.usagecore.performance.PerformanceSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Runs EXPLAIN (ANALYZE, BUFFERS) for the current hot-path SQL.
 * Prints summaries only — not a full plan dump.
 */
public final class ExplainAnalyzeCapture {

    private ExplainAnalyzeCapture() {
    }

    public static void main(String[] args) throws Exception {
        UUID tenantId = PerformanceSettings.tenantId();
        Instant now = Instant.parse("2026-08-18T12:00:00Z");
        try (Connection connection = LabJdbc.open()) {
            connection.setAutoCommit(true);
            explain(
                    connection,
                    "entitlement-effective-lookup",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT c.id, cv.id, cv.version_number, e.entitlement_mode, e.limit_quantity
                    FROM contract c
                    INNER JOIN product p ON p.id = c.product_id
                    INNER JOIN contract_version cv ON cv.contract_id = c.id AND cv.tenant_id = c.tenant_id
                    INNER JOIN entitlement e ON e.contract_version_id = cv.id
                    INNER JOIN feature f ON f.id = e.feature_id AND f.product_id = p.id
                    WHERE c.tenant_id = ?
                      AND p.product_key = ?
                      AND f.feature_key = ?
                      AND cv.status = 'ACTIVATED'
                      AND cv.effective_from <= ?
                      AND (cv.effective_until IS NULL OR cv.effective_until > ?)
                    """,
                    ps -> {
                        ps.setObject(1, tenantId);
                        ps.setString(2, PerformanceSettings.productKey());
                        ps.setString(3, PerformanceSettings.featureKey());
                        Timestamp ts = Timestamp.from(now);
                        ps.setTimestamp(4, ts);
                        ps.setTimestamp(5, ts);
                    }
            );
            explain(
                    connection,
                    "meter-definition-lookup",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT md.id
                    FROM meter_definition md
                    INNER JOIN product p ON p.id = md.product_id
                    WHERE p.product_key = ? AND md.meter_key = ? AND md.status = 'ACTIVE' AND p.status = 'ACTIVE'
                    """,
                    ps -> {
                        ps.setString(1, PerformanceSettings.productKey());
                        ps.setString(2, PerformanceSettings.eventsMeterKey());
                    }
            );
            explain(
                    connection,
                    "usage-ingestion-idempotency-lookup",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT id FROM usage_ingestion
                    WHERE tenant_id = ? AND idempotency_key = ?
                    """,
                    ps -> {
                        ps.setObject(1, tenantId);
                        ps.setString(2, "perf-explain-missing-key");
                    }
            );
            explain(
                    connection,
                    "outbox-pending-claim",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT id FROM outbox_event
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT 50
                    """,
                    ps -> {
                    }
            );
            explain(
                    connection,
                    "commercial-period-covering",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT id FROM commercial_period
                    WHERE tenant_id = ? AND product_id = (
                        SELECT id FROM product WHERE product_key = ?
                    ) AND period_start <= ? AND period_end > ?
                    """,
                    ps -> {
                        ps.setObject(1, tenantId);
                        ps.setString(2, PerformanceSettings.productKey());
                        Timestamp ts = Timestamp.from(now);
                        ps.setTimestamp(3, ts);
                        ps.setTimestamp(4, ts);
                    }
            );
            explain(
                    connection,
                    "quota-state-lookup",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT consumed_quantity
                    FROM quota_state
                    WHERE tenant_id = ?
                      AND meter_definition_id = (
                        SELECT md.id FROM meter_definition md
                        JOIN product p ON p.id = md.product_id
                        WHERE p.product_key = ? AND md.meter_key = ?
                      )
                    """,
                    ps -> {
                        ps.setObject(1, tenantId);
                        ps.setString(2, PerformanceSettings.productKey());
                        ps.setString(3, PerformanceSettings.consumeMeterKey());
                    }
            );
            explain(
                    connection,
                    "processed-event-by-id",
                    """
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                    SELECT COUNT(*) FROM processed_event WHERE event_id = ?
                    """,
                    ps -> ps.setObject(1, UUID.fromString("00000000-0000-0000-0000-000000000001"))
            );
        }
    }

    private static void explain(Connection connection, String name, String sql, Binder binder) throws SQLException {
        System.out.println();
        System.out.println("=== " + name + " ===");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> interesting = List.of(
                        "Seq Scan",
                        "Index",
                        "Bitmap",
                        "Nested Loop",
                        "Hash",
                        "Sort",
                        "LockRows",
                        "execution time",
                        "Planning Time",
                        "Execution Time",
                        "Buffers",
                        "rows=",
                        "cost="
                );
                while (rs.next()) {
                    String line = rs.getString(1);
                    boolean keep = false;
                    for (String token : interesting) {
                        if (line.contains(token)) {
                            keep = true;
                            break;
                        }
                    }
                    if (keep) {
                        System.out.println(line);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
