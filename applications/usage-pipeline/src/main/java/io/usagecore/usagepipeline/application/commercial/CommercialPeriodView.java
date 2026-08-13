package io.usagecore.usagepipeline.application.commercial;

import java.time.Instant;
import java.util.UUID;

/**
 * Narrow read model of a covering commercial period.
 */
public record CommercialPeriodView(
        UUID id,
        UUID tenantId,
        UUID productId,
        Instant periodStart,
        Instant periodEnd,
        CommercialPeriodStatus status
) {
}
