package io.usagecore.usagepipeline.application.commercial;

import java.time.Instant;
import java.util.UUID;

public record CommercialUsageExceptionRecord(
        UUID id,
        UUID eventId,
        UUID tenantId,
        UUID productId,
        UUID meterDefinitionId,
        UUID commercialPeriodId,
        String reason,
        Instant occurredAt,
        Instant recordedAt,
        String correlationId
) {
}
