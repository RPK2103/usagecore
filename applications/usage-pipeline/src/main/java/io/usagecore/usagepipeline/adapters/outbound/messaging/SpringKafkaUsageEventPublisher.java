package io.usagecore.usagepipeline.adapters.outbound.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.usagepipeline.adapters.observability.OutboxPublishSpanSupport;
import io.usagecore.usagepipeline.application.usage.UsageEventPublisher;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes the exact stored outbox envelope JSON to Kafka and waits for broker acknowledgement.
 * W3C {@code traceparent} is injected by Spring Kafka observation from the restored outbox span.
 */
@Component
public class SpringKafkaUsageEventPublisher implements UsageEventPublisher {

    public static final String HEADER_CORRELATION_ID = "correlationId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_EVENT_VERSION = "eventVersion";
    public static final String HEADER_EVENT_ID = "eventId";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties kafkaProperties;
    private final OutboxPublishSpanSupport outboxPublishSpanSupport;
    private final ObjectMapper objectMapper;

    public SpringKafkaUsageEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaProperties kafkaProperties,
            OutboxPublishSpanSupport outboxPublishSpanSupport,
            ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.outboxPublishSpanSupport = outboxPublishSpanSupport;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishSerialized(
            String topic,
            String partitionKey,
            String serializedEnvelope,
            String eventId,
            String eventType,
            String eventVersion,
            String correlationId
    ) {
        try (OutboxPublishSpanSupport.Scope ignored = outboxPublishSpanSupport.open(
                traceEvidenceFromEnvelope(serializedEnvelope)
        )) {
            doPublish(topic, partitionKey, serializedEnvelope, eventId, eventType, eventVersion, correlationId);
        }
    }

    private void doPublish(
            String topic,
            String partitionKey,
            String serializedEnvelope,
            String eventId,
            String eventType,
            String eventVersion,
            String correlationId
    ) {
        String resolvedTopic = topic != null ? topic : kafkaProperties.topics().usageReceived();
        ProducerRecord<String, String> record = new ProducerRecord<>(resolvedTopic, partitionKey, serializedEnvelope);
        if (correlationId != null) {
            record.headers().add(new RecordHeader(HEADER_CORRELATION_ID, correlationId.getBytes()));
        }
        record.headers().add(new RecordHeader(HEADER_EVENT_TYPE, eventType.getBytes()));
        record.headers().add(new RecordHeader(HEADER_EVENT_VERSION, eventVersion.getBytes()));
        record.headers().add(new RecordHeader(HEADER_EVENT_ID, eventId.getBytes()));

        long timeoutMillis = kafkaProperties.publishTimeout().toMillis();
        try {
            SendResult<String, String> result = kafkaTemplate.send(record)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null || result.getRecordMetadata() == null) {
                throw new UsagePublicationException("Kafka publication returned no metadata");
            }
        } catch (TimeoutException ex) {
            throw new UsagePublicationException("Kafka publication timed out waiting for acknowledgement", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UsagePublicationException("Kafka publication interrupted", ex);
        } catch (UsagePublicationException ex) {
            throw ex;
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new UsagePublicationException("Kafka publication failed", cause);
        }
    }

    private String traceEvidenceFromEnvelope(String serializedEnvelope) {
        try {
            JsonNode node = objectMapper.readTree(serializedEnvelope);
            JsonNode traceId = node.get("traceId");
            return traceId != null && !traceId.isNull() ? traceId.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
