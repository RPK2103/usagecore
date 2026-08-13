package io.usagecore.usagepipeline.adapters.outbound.persistence;

import io.usagecore.usagepipeline.application.usage.ActiveMeterDefinition;
import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.AggregationWindow;
import io.usagecore.usagepipeline.application.usage.MeterDefinitionLookup;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Narrow JDBC read of Control Plane–owned meter_definition rows.
 * No compile-time dependency on the Control Plane application.
 */
@Repository
public class JdbcMeterDefinitionLookup implements MeterDefinitionLookup {

    private static final RowMapper<ActiveMeterDefinition> ROW_MAPPER = (rs, rowNum) -> new ActiveMeterDefinition(
            rs.getObject("meter_definition_id", java.util.UUID.class),
            rs.getObject("product_id", java.util.UUID.class),
            rs.getString("product_key"),
            rs.getString("meter_key"),
            AggregationType.valueOf(rs.getString("aggregation_type")),
            AggregationWindow.valueOf(rs.getString("aggregation_window"))
    );

    private static final String FIND_ACTIVE = """
            SELECT
                md.id AS meter_definition_id,
                p.id AS product_id,
                p.product_key AS product_key,
                md.meter_key AS meter_key,
                md.aggregation_type AS aggregation_type,
                md.aggregation_window AS aggregation_window
            FROM meter_definition md
            INNER JOIN product p ON p.id = md.product_id
            WHERE p.product_key = ?
              AND md.meter_key = ?
              AND md.status = 'ACTIVE'
              AND p.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMeterDefinitionLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ActiveMeterDefinition> findActiveByProductKeyAndMeterKey(String productKey, String meterKey) {
        List<ActiveMeterDefinition> rows = jdbcTemplate.query(FIND_ACTIVE, ROW_MAPPER, productKey, meterKey);
        return rows.stream().findFirst();
    }
}
