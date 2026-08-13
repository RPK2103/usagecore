package io.usagecore.usagepipeline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds DataPilot Cloud product + Phase 6A demo meters for usage-pipeline tests.
 */
public final class MeterDefinitionFixtureSeeder {

    public static final String PRODUCT_KEY = "datapilot-cloud";
    public static final String METER_API_REQUESTS = "api_requests";
    public static final String METER_SCHEDULED_EXPORT = "scheduled_export";
    public static final String METER_WORKSPACE_SIZE = "workspace_size";

    private final JdbcTemplate jdbc;

    public MeterDefinitionFixtureSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID ensureDataPilotProductAndMeters() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID productId = UUID.nameUUIDFromBytes(PRODUCT_KEY.getBytes());
        jdbc.update(
                """
                INSERT INTO product (id, product_key, name, status, created_at, updated_at)
                VALUES (?, ?, 'DataPilot Cloud', 'ACTIVE', ?, ?)
                ON CONFLICT (product_key) DO NOTHING
                """,
                productId,
                PRODUCT_KEY,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        UUID resolvedProductId = jdbc.queryForObject(
                "SELECT id FROM product WHERE product_key = ?",
                UUID.class,
                PRODUCT_KEY
        );
        ensureMeter(resolvedProductId, METER_API_REQUESTS, "API Requests", "SUM", now);
        ensureMeter(resolvedProductId, METER_SCHEDULED_EXPORT, "Scheduled Export", "COUNT", now);
        ensureMeter(resolvedProductId, METER_WORKSPACE_SIZE, "Workspace Size", "MAX", now);
        return resolvedProductId;
    }

    public void ensureInactiveMeter(String meterKey) {
        UUID productId = ensureDataPilotProductAndMeters();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, meter_key, display_name, aggregation_type, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'SUM', 'INACTIVE', ?, ?)
                ON CONFLICT (product_id, meter_key) DO UPDATE SET status = 'INACTIVE', updated_at = EXCLUDED.updated_at
                """,
                UUID.nameUUIDFromBytes(("inactive-" + meterKey).getBytes()),
                productId,
                meterKey,
                meterKey,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public UUID meterDefinitionId(String meterKey) {
        return jdbc.queryForObject(
                """
                SELECT md.id FROM meter_definition md
                JOIN product p ON p.id = md.product_id
                WHERE p.product_key = ? AND md.meter_key = ?
                """,
                UUID.class,
                PRODUCT_KEY,
                meterKey
        );
    }

    private void ensureMeter(
            UUID productId,
            String meterKey,
            String displayName,
            String aggregationType,
            Instant now
    ) {
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, meter_key, display_name, aggregation_type, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (product_id, meter_key) DO NOTHING
                """,
                UUID.nameUUIDFromBytes((PRODUCT_KEY + "|" + meterKey).getBytes()),
                productId,
                meterKey,
                displayName,
                aggregationType,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }
}
