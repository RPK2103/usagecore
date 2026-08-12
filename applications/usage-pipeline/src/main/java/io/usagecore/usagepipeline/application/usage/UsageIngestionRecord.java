package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.UUID;

public record UsageIngestionRecord(
        UUID id,
        UUID eventId,
        UUID tenantId,
        String principalId,
        String productKey,
        String meterKey,
        long quantity,
        Instant occurredAt,
        String idempotencyKey,
        String correlationId,
        Instant acceptedAt
) {
}
