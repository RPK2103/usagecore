package io.usagecore.usagepipeline.application.usage;

import java.util.Optional;

/**
 * ADR-007-style narrow JDBC read of Control Plane–owned {@code meter_definition}.
 * Usage Pipeline must not import Control Plane classes.
 */
public interface MeterDefinitionLookup {

    /**
     * Resolves an ACTIVE meter by product and meter keys.
     * Inactive or missing meters are absent.
     */
    Optional<ActiveMeterDefinition> findActiveByProductKeyAndMeterKey(String productKey, String meterKey);
}
