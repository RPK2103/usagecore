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
 * {@code correlationId} is application/business request correlation (HTTP {@code X-Correlation-Id}).
 * {@code traceId} is distributed-trace evidence: W3C {@code traceparent} when captured, otherwise
 * a hex OpenTelemetry trace id. The two identifiers are distinct and may differ.
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
