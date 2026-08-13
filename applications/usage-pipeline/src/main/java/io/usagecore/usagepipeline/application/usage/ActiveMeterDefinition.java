package io.usagecore.usagepipeline.application.usage;

import java.util.UUID;

/**
 * Narrow read model of an active meter definition from shared commercial configuration.
 */
public record ActiveMeterDefinition(
        UUID meterDefinitionId,
        UUID productId,
        String productKey,
        String meterKey,
        AggregationType aggregationType,
        AggregationWindow aggregationWindow
) {
}
