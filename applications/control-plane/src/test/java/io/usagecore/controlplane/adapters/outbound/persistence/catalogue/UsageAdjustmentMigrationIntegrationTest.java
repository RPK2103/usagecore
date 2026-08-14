package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves V13 upgrades a legitimate V12 database that already has reconciliation evidence
 * without rewriting completed reports, and adds usage_adjustment uniquely.
 */
@Testcontainers(disabledWithoutDocker = true)
class UsageAdjustmentMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    @Test
    void v12ReconciliationRowsSurviveV13_andUsageAdjustmentIsCreated() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("12")
                .load()
                .migrate();

        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID productId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID meterId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID featureId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff");
        UUID periodId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO tenant (id, tenant_key, display_name, status, created_at, updated_at)
                VALUES (?, 'legacy-tenant', 'Legacy', 'ACTIVE', ?, ?)
                """,
                tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO product (id, product_key, name, status, created_at, updated_at)
                VALUES (?, 'legacy-product', 'Legacy Product', 'ACTIVE', ?, ?)
                """,
                productId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO feature (id, product_id, feature_key, name, status, created_at, updated_at)
                VALUES (?, ?, 'api_access', 'API Access', 'ACTIVE', ?, ?)
                """,
                featureId,
                productId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, 'api_requests', 'API Requests', 'SUM', 'MONTHLY', 'ACTIVE', ?, ?)
                """,
                meterId,
                productId,
                featureId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO commercial_period (
                    id, tenant_id, product_id, period_start, period_end, status,
                    created_at, updated_at, closing_started_at, reconciling_started_at,
                    finalized_at, finalized_by
                ) VALUES (?, ?, ?, ?, ?, 'FINALIZED', ?, ?, ?, ?, ?, 'migrator')
                """,
                periodId,
                tenantId,
                productId,
                Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-02T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-03T00:00:00Z"))
        );
        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    id, tenant_id, product_id, commercial_period_id, status, result,
                    started_at, completed_at, started_by,
                    canonical_event_count, quarantined_event_count,
                    matched_meter_count, mismatched_meter_count,
                    correlation_id, failure_reason
                ) VALUES (?, ?, ?, ?, 'COMPLETED', 'MATCH', ?, ?, 'seeder', 1, 0, 1, 0, NULL, NULL)
                """,
                runId,
                tenantId,
                productId,
                periodId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1))
        );
        jdbc.update(
                """
                INSERT INTO reconciliation_item (
                    id, reconciliation_run_id, meter_definition_id, meter_key, aggregation_type,
                    window_start, window_end,
                    observed_expected_value, commercial_expected_value, actual_value, difference,
                    expected_event_count, actual_event_count,
                    quarantined_event_count, observed_event_count, quota_consumed_value,
                    status, classification
                ) VALUES (?, ?, ?, 'api_requests', 'SUM', ?, ?, 97, 97, 97, 0, 1, 1, 0, 1, NULL, 'MATCH', 'MATCH')
                """,
                itemId,
                runId,
                meterId,
                Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"))
        );

        assertThat(columnExists(jdbc, "reconciliation_item", "adjusted_event_count")).isFalse();
        assertThat(tableExists(jdbc, "usage_adjustment")).isFalse();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(columnExists(jdbc, "reconciliation_item", "adjusted_event_count")).isTrue();
        assertThat(columnExists(jdbc, "reconciliation_item", "unresolved_exception_count")).isTrue();
        assertThat(tableExists(jdbc, "usage_adjustment")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT adjusted_event_count FROM reconciliation_item WHERE id = ?",
                Long.class,
                itemId
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT result FROM reconciliation_run WHERE id = ?",
                String.class,
                runId
        )).isEqualTo("MATCH");
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = ? AND column_name = ?
                )
                """,
                Boolean.class,
                table,
                column
        );
        return Boolean.TRUE.equals(exists);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_name = ?
                )
                """,
                Boolean.class,
                table
        );
        return Boolean.TRUE.equals(exists);
    }
}
