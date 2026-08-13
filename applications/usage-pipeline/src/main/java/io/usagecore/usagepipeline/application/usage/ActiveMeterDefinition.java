package io.usagecore.usagepipeline.application.usage;

import java.util.UUID;

/**
 * Narrow read model of an active meter definition from shared commercial configuration.
 * {@code featureId}/{@code featureKey} identify the contractual Feature that governs quota
 * when bound; both may be null for legacy pre-V10 unbound meters.
 */
public record ActiveMeterDefinition(
        UUID meterDefinitionId,
        UUID productId,
        String productKey,
        String meterKey,
        UUID featureId,
        String featureKey,
        AggregationType aggregationType,
        AggregationWindow aggregationWindow
) {
}
