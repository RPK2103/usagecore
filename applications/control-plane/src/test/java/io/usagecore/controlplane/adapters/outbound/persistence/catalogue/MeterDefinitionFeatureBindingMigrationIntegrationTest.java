package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Proves V10 upgrades a legitimate V9 database that already has MeterDefinition rows
 * without inventing feature bindings, and that new Phase 6C meters still require feature_id.
 */
@Testcontainers(disabledWithoutDocker = true)
class MeterDefinitionFeatureBindingMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    @Test
    void v9MeterSurvivesV10UpgradeUnbound_andNewMetersRequireFeatureId() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID productId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID meterId = UUID.fromString("11111111-2222-3333-4444-555555555555");

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
                INSERT INTO meter_definition (
                    id, product_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, 'legacy_api_requests', 'Legacy API Requests', 'SUM', 'MONTHLY', 'ACTIVE', ?, ?)
                """,
                meterId,
                productId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        assertThat(columnExists(jdbc, "meter_definition", "feature_id")).isFalse();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(columnExists(jdbc, "meter_definition", "feature_id")).isTrue();
        UUID featureId = jdbc.queryForObject(
                "SELECT feature_id FROM meter_definition WHERE id = ?",
                UUID.class,
                meterId
        );
        assertThat(featureId).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT meter_key FROM meter_definition WHERE id = ?",
                String.class,
                meterId
        )).isEqualTo("legacy_api_requests");

        UUID featureForNew = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-ffffffffffff");
        jdbc.update(
                """
                INSERT INTO feature (id, product_id, feature_key, name, status, created_at, updated_at)
                VALUES (?, ?, 'api_access', 'API Access', 'ACTIVE', ?, ?)
                """,
                featureForNew,
                productId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        UUID boundMeterId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, 'bound_api_requests', 'Bound API Requests', 'SUM', 'MONTHLY', 'ACTIVE', ?, ?)
                """,
                boundMeterId,
                productId,
                featureForNew,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        assertThat(jdbc.queryForObject(
                "SELECT feature_id FROM meter_definition WHERE id = ?",
                UUID.class,
                boundMeterId
        )).isEqualTo(featureForNew);

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO meter_definition (
                    id, product_id, feature_id, meter_key, display_name, aggregation_type, aggregation_window,
                    status, created_at, updated_at
                ) VALUES (?, ?, NULL, 'unbound_new_meter', 'Unbound New', 'SUM', 'MONTHLY', 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                productId,
                Timestamp.from(now),
                Timestamp.from(now)
        )).hasMessageContaining("ck_meter_definition_feature_id_required");
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
}
