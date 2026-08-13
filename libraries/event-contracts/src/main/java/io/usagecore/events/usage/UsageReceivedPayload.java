package io.usagecore.events.usage;

/**
 * Payload for {@link io.usagecore.events.EventTypes#USAGE_RECEIVED}.
 * <p>
 * {@code idempotencyKey} is the caller's logical usage operation key (HTTP ingestion dedup).
 * Consumer redelivery deduplication uses envelope {@code eventId}, not this key.
 */
public record UsageReceivedPayload(
        String productKey,
        String meterKey,
        long quantity,
        String idempotencyKey,
        String principalSubject
) {
}
