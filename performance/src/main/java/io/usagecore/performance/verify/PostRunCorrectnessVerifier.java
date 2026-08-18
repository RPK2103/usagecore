package io.usagecore.performance.verify;

import io.usagecore.performance.LabJdbc;
import io.usagecore.performance.PerformanceSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Post-run commercial correctness checks. A fast HTTP lab that corrupts state is a failed run.
 */
public final class PostRunCorrectnessVerifier {

    private PostRunCorrectnessVerifier() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        try (Connection connection = LabJdbc.open()) {
            switch (mode) {
                case "ingestion" -> verifyIngestion(connection);
                case "quota" -> verifyQuota(connection);
                case "drain" -> waitForProcessingComplete(connection);
                case "all" -> {
                    waitForProcessingComplete(connection);
                    verifyIngestion(connection);
                    verifyQuota(connection);
                }
                default -> throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        }
    }

    public static void waitForProcessingComplete(Connection connection) throws SQLException, InterruptedException {
        waitForOutboxDrain(connection);
        UUID tenantId = PerformanceSettings.tenantId();
        Instant deadline = Instant.now().plusSeconds(PerformanceSettings.drainWaitSeconds());
        Instant started = Instant.now();
        long ingestions;
        long processed;
        long ledger;
        do {
            ingestions = countByTenant(connection, "SELECT COUNT(*) FROM usage_ingestion WHERE tenant_id = ?", tenantId);
            processed = countByTenant(connection, "SELECT COUNT(*) FROM processed_event WHERE tenant_id = ?", tenantId);
            ledger = countByTenant(connection, "SELECT COUNT(*) FROM usage_ledger WHERE tenant_id = ?", tenantId);
            if (ingestions == processed && ingestions == ledger) {
                System.out.println(
                        "Consumer catch-up: ingestion=processed=ledger=" + ingestions
                                + " in " + Duration.between(started, Instant.now()).toMillis() + " ms after PENDING=0"
                );
                return;
            }
            Thread.sleep(250);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException(
                "Consumer did not catch up: ingestion=" + ingestions + " processed=" + processed + " ledger=" + ledger
        );
    }

    public static void waitForOutboxDrain(Connection connection) throws SQLException, InterruptedException {
        UUID tenantId = PerformanceSettings.tenantId();
        Instant deadline = Instant.now().plusSeconds(PerformanceSettings.drainWaitSeconds());
        long pending = pendingOutbox(connection);
        Instant started = Instant.now();
        while (pending > 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(250);
            pending = pendingOutbox(connection);
        }
        Duration waited = Duration.between(started, Instant.now());
        if (pending > 0) {
            throw new IllegalStateException(
                    "Outbox still PENDING=" + pending + " for tenant " + tenantId
                            + " after " + waited.toSeconds() + "s"
            );
        }
        System.out.println("Outbox drained to PENDING=0 in " + waited.toMillis() + " ms");
    }

    public static void verifyIngestion(Connection connection) throws SQLException {
        UUID tenantId = PerformanceSettings.tenantId();
        long ingestions = countByTenant(connection, "SELECT COUNT(*) FROM usage_ingestion WHERE tenant_id = ?", tenantId);
        long processed = countByTenant(connection, "SELECT COUNT(*) FROM processed_event WHERE tenant_id = ?", tenantId);
        long ledger = countByTenant(connection, "SELECT COUNT(*) FROM usage_ledger WHERE tenant_id = ?", tenantId);
        long pending = pendingOutbox(connection);
        long published = countByTenant(
                connection,
                """
                SELECT COUNT(*)
                FROM outbox_event o
                INNER JOIN usage_ingestion u ON u.event_id = o.event_id
                WHERE o.status = 'PUBLISHED' AND u.tenant_id = ?
                """,
                tenantId
        );
        System.out.println("Ingestion correctness");
        System.out.println("  usage_ingestion=" + ingestions);
        System.out.println("  processed_event=" + processed);
        System.out.println("  usage_ledger=" + ledger);
        System.out.println("  outbox PENDING=" + pending + " PUBLISHED=" + published);
        if (pending > 0) {
            throw new IllegalStateException("Async path has not drained; PENDING=" + pending);
        }
        if (ingestions != processed || ingestions != ledger) {
            throw new IllegalStateException(
                    "Accepted distinct ingestions must eventually equal processed_event and usage_ledger for this tenant"
            );
        }
        System.out.println("  RESULT: ingestion counts match");
    }

    public static void verifyQuota(Connection connection) throws SQLException {
        UUID tenantId = PerformanceSettings.tenantId();
        long accepted = countByTenant(
                connection,
                "SELECT COUNT(*) FROM quota_consumption WHERE tenant_id = ? AND decision = 'ACCEPTED'",
                tenantId
        );
        long rejected = countByTenant(
                connection,
                "SELECT COUNT(*) FROM quota_consumption WHERE tenant_id = ? AND decision = 'REJECTED'",
                tenantId
        );
        System.out.println("Quota correctness");
        System.out.println("  acceptedRows=" + accepted);
        System.out.println("  rejectedRows=" + rejected);
        int metersChecked = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT md.meter_key, qs.window_start, qs.window_end,
                       qs.consumed_quantity, qs.configured_limit
                FROM quota_state qs
                JOIN meter_definition md ON md.id = qs.meter_definition_id
                WHERE qs.tenant_id = ?
                ORDER BY md.meter_key, qs.window_start
                """
        )) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String meterKey = rs.getString("meter_key");
                    long consumed = rs.getLong("consumed_quantity");
                    long limit = rs.getLong("configured_limit");
                    long contribution = acceptedContribution(
                            connection,
                            tenantId,
                            meterKey,
                            rs.getTimestamp("window_start").toInstant(),
                            rs.getTimestamp("window_end").toInstant()
                    );
                    System.out.println(
                            "  meter=" + meterKey
                                    + " acceptedContribution=" + contribution
                                    + " consumed=" + consumed
                                    + " limit=" + limit
                    );
                    QuotaCorrectnessRules.assertMeterState(meterKey, contribution, consumed, limit);
                    metersChecked++;
                }
            }
        }
        if (metersChecked == 0) {
            System.out.println("  RESULT: no quota_state rows for tenant (nothing to compare)");
            return;
        }
        System.out.println("  RESULT: " + metersChecked + " meter(s) consumed <= limit and match ACCEPTED contribution");
    }

    public static long pendingOutbox(Connection connection) throws SQLException {
        return countByTenant(
                connection,
                """
                SELECT COUNT(*)
                FROM outbox_event o
                INNER JOIN usage_ingestion u ON u.event_id = o.event_id
                WHERE o.status = 'PENDING' AND u.tenant_id = ?
                """,
                PerformanceSettings.tenantId()
        );
    }

    private static long acceptedContribution(
            Connection connection,
            UUID tenantId,
            String meterKey,
            Instant windowStart,
            Instant windowEnd
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT COALESCE(SUM(contribution), 0)
                FROM quota_consumption
                WHERE tenant_id = ? AND meter_key = ? AND decision = 'ACCEPTED'
                  AND window_start = ? AND window_end = ?
                """
        )) {
            ps.setObject(1, tenantId);
            ps.setString(2, meterKey);
            ps.setTimestamp(3, Timestamp.from(windowStart));
            ps.setTimestamp(4, Timestamp.from(windowEnd));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countByTenant(Connection connection, String sql, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

}
