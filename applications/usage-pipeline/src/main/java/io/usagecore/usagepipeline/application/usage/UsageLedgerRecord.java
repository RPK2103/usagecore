package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical immutable usage ledger entry for one accepted UsageReceived event.
 * Not an aggregate; do not mutate after insert under normal processing.
 * {@code isLate} records whether processing time was at/after the event-time window end.
 */
public record UsageLedgerRecord(
        UUID id,
        UUID eventId,
        UUID tenantId,
        String productKey,
        String meterKey,
        long quantity,
        Instant occurredAt,
        String idempotencyKey,
        String correlationId,
        String principalId,
        Instant recordedAt,
        boolean isLate
) {
}
