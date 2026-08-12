package io.usagecore.usagepipeline.application.usage;

import java.util.UUID;

public record UsageIngestionResult(
        UUID eventId,
        String status,
        String correlationId
) {
}
