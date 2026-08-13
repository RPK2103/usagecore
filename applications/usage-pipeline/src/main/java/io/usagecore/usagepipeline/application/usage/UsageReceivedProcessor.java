package io.usagecore.usagepipeline.application.usage;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;

/**
 * Inbound application port for consumed UsageReceived events.
 * <p>
 * Phase 5B: idempotent inbox claim + canonical ledger write keyed by {@code eventId}.
 */
public interface UsageReceivedProcessor {

    void process(EventEnvelope<UsageReceivedPayload> event);
}
