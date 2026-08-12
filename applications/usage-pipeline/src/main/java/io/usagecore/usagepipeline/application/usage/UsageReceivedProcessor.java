package io.usagecore.usagepipeline.application.usage;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;

/**
 * Inbound application port for consumed UsageReceived events.
 * <p>
 * Phase 4 responsibility: deserialize/validate wiring only. No commercial side effects.
 * Processing is <strong>not</strong> claimed to be idempotent yet — Phase 5 adds inbox/dedup.
 */
public interface UsageReceivedProcessor {

    void process(EventEnvelope<UsageReceivedPayload> event);
}
