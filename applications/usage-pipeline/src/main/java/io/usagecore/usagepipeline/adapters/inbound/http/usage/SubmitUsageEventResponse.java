package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import java.util.UUID;

public record SubmitUsageEventResponse(
        UUID eventId,
        String status,
        String correlationId,
        boolean idempotentReplay
) {
}
