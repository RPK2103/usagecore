package io.usagecore.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned Kafka transport envelope.
 * <p>
 * {@code eventId} identifies this emitted event instance.
 * Caller {@code idempotencyKey} (when present in payload) identifies the caller's logical
 * operation and is distinct from {@code eventId}.
 * <p>
 * Never place JWT credentials or secrets in the envelope.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        UUID tenantId,
        String aggregateId,
        String correlationId,
        String causationId,
        String traceId,
        Instant publishedAt,
        T payload
) {
}
