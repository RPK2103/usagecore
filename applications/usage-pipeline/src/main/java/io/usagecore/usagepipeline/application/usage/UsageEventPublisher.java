package io.usagecore.usagepipeline.application.usage;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;

/**
 * Outbound port for publishing UsageReceived events to Kafka.
 * Implementations must wait for broker acknowledgement before returning successfully.
 */
public interface UsageEventPublisher {

    void publish(EventEnvelope<UsageReceivedPayload> event, String partitionKey);
}
