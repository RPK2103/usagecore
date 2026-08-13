package io.usagecore.usagepipeline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds commercial periods for Usage Pipeline Phase 7 tests.
 */
public final class CommercialPeriodFixtureSeeder {

    private final JdbcTemplate jdbc;

    public CommercialPeriodFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertPeriod(
            UUID tenantId,
            UUID productId,
            Instant periodStart,
            Instant periodEnd,
            String status
    ) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        Instant closingAt = null;
        Instant reconcilingAt = null;
        Instant finalizedAt = null;
        String finalizedBy = null;
        switch (status) {
            case "CLOSING" -> closingAt = Instant.parse("2026-09-01T00:00:00Z");
            case "RECONCILING" -> {
                closingAt = Instant.parse("2026-09-01T00:00:00Z");
                reconcilingAt = Instant.parse("2026-09-02T00:00:00Z");
            }
            case "FINALIZED" -> {
                closingAt = Instant.parse("2026-09-01T00:00:00Z");
                reconcilingAt = Instant.parse("2026-09-02T00:00:00Z");
                finalizedAt = Instant.parse("2026-09-03T00:00:00Z");
                finalizedBy = "test-finalizer";
            }
            case "OPEN" -> {
                // timestamps remain null
            }
            default -> throw new IllegalArgumentException("Unsupported status: " + status);
        }

        jdbc.update(
                """
                INSERT INTO commercial_period (
                    id, tenant_id, product_id, period_start, period_end, status,
                    created_at, updated_at, closing_started_at, reconciling_started_at,
                    finalized_at, finalized_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                tenantId,
                productId,
                Timestamp.from(periodStart),
                Timestamp.from(periodEnd),
                status,
                Timestamp.from(now),
                Timestamp.from(now),
                closingAt == null ? null : Timestamp.from(closingAt),
                reconcilingAt == null ? null : Timestamp.from(reconcilingAt),
                finalizedAt == null ? null : Timestamp.from(finalizedAt),
                finalizedBy
        );
        return id;
    }

    public void clearCommercialTables() {
        jdbc.update("DELETE FROM commercial_usage_exception");
        jdbc.update("DELETE FROM commercial_period_transition");
        jdbc.update("DELETE FROM commercial_period");
    }

    public long exceptionCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM commercial_usage_exception", Long.class);
        return count == null ? 0L : count;
    }

    public long exceptionCountForEvent(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commercial_usage_exception WHERE event_id = ?",
                Long.class,
                eventId
        );
        return count == null ? 0L : count;
    }

    public String periodStatus(UUID periodId) {
        return jdbc.queryForObject(
                "SELECT status FROM commercial_period WHERE id = ?",
                String.class,
                periodId
        );
    }
}
