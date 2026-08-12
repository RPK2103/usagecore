package io.usagecore.usagepipeline.application.usage;

/**
 * Publishes a pre-serialized UsageReceived envelope to Kafka and waits for broker acknowledgement.
 * Used by the transactional outbox publisher; HTTP ingestion never calls this synchronously.
 */
public interface UsageEventPublisher {

    void publishSerialized(
            String topic,
            String partitionKey,
            String serializedEnvelope,
            String eventId,
            String eventType,
            String eventVersion,
            String correlationId
    );
}
