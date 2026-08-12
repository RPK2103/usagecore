package io.usagecore.usagepipeline.application.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventRecord(
        UUID id,
        UUID eventId,
        String eventType,
        String eventVersion,
        String topic,
        String partitionKey,
        String serializedEnvelope,
        OutboxStatus status,
        Instant createdAt,
        Instant publishedAt
) {
}
