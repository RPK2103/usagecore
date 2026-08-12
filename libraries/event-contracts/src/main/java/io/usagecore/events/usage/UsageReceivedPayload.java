package io.usagecore.events.usage;

/**
 * Payload for {@link io.usagecore.events.EventTypes#USAGE_RECEIVED}.
 * <p>
 * {@code idempotencyKey} is the caller's logical usage operation key (Phase 5 deduplication key).
 * It is not the envelope {@code eventId}.
 */
public record UsageReceivedPayload(
        String productKey,
        String meterKey,
        long quantity,
        String idempotencyKey,
        String principalSubject
) {
}
