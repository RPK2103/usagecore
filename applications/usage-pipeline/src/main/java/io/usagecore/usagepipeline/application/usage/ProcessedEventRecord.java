package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Successfully processed Kafka event claim (consumer inbox).
 * Deduplication key is {@code eventId}, not HTTP {@code idempotencyKey}.
 */
public record ProcessedEventRecord(
        UUID eventId,
        String eventType,
        String eventVersion,
        UUID tenantId,
        String consumerName,
        Instant processedAt,
        String correlationId
) {
}
