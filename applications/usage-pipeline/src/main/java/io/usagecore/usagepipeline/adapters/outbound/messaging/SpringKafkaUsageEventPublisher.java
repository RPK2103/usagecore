package io.usagecore.usagepipeline.adapters.outbound.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;
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
 * Spring Kafka publisher that waits for broker acknowledgement before returning.
 * Not fire-and-forget; not transactional outbox (Phase 5+).
 */
@Component
public class SpringKafkaUsageEventPublisher implements UsageEventPublisher {

    public static final String HEADER_CORRELATION_ID = "correlationId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_EVENT_VERSION = "eventVersion";
    public static final String HEADER_EVENT_ID = "eventId";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaProperties kafkaProperties;

    public SpringKafkaUsageEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaProperties kafkaProperties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public void publish(EventEnvelope<UsageReceivedPayload> event, String partitionKey) {
        String topic = kafkaProperties.topics().usageReceived();
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new UsagePublicationException("Failed to serialize usage event", ex);
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, json);
        if (event.correlationId() != null) {
            record.headers().add(new RecordHeader(HEADER_CORRELATION_ID, event.correlationId().getBytes()));
        }
        record.headers().add(new RecordHeader(HEADER_EVENT_TYPE, event.eventType().getBytes()));
        record.headers().add(new RecordHeader(HEADER_EVENT_VERSION, event.eventVersion().getBytes()));
        record.headers().add(new RecordHeader(HEADER_EVENT_ID, event.eventId().toString().getBytes()));

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
}
