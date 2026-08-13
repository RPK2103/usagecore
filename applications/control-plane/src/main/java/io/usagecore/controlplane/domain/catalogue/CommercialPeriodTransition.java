package io.usagecore.controlplane.domain.catalogue;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only evidence of a commercial period lifecycle transition.
 */
public record CommercialPeriodTransition(
        UUID id,
        UUID commercialPeriodId,
        CommercialPeriodStatus fromStatus,
        CommercialPeriodStatus toStatus,
        String principalId,
        Instant occurredAt,
        String correlationId
) {
}
