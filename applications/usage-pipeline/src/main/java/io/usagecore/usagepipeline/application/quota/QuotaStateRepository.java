package io.usagecore.usagepipeline.application.quota;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative synchronous quota admission counter per tenant/meter/window.
 */
public interface QuotaStateRepository {

    /**
     * Ensures a zero-consumed row exists for the window, then attempts an atomic increment
     * only if {@code consumed + contribution <= configuredLimit}.
     *
     * @return new consumed quantity when accepted; empty when the conditional update loses
     */
    Optional<Long> tryConsume(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd,
            long configuredLimit,
            long contribution,
            Instant updatedAt
    );

    Optional<Long> findConsumed(
            UUID tenantId,
            UUID meterDefinitionId,
            Instant windowStart,
            Instant windowEnd
    );
}
