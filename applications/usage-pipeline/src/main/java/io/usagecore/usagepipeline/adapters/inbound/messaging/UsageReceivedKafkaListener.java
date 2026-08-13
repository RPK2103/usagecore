package io.usagecore.usagepipeline.adapters.inbound.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.InvalidUsageEventException;
import io.usagecore.usagepipeline.application.usage.UnsupportedUsageEventException;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code usagecore.usage.received.v1}.
 * <p>
 * Offset acknowledgement follows Spring Kafka {@code ack-mode: record}: the container
 * acknowledges only after this listener returns successfully (after the DB transaction
 * committed inside {@link UsageReceivedProcessor}). Exceptions prevent acknowledgement
 * so transient failures remain redeliverable.
 */
@Component
public class UsageReceivedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(UsageReceivedKafkaListener.class);

    private final ObjectMapper objectMapper;
    private final UsageReceivedProcessor usageReceivedProcessor;
    private final KafkaProperties kafkaProperties;

    public UsageReceivedKafkaListener(
            ObjectMapper objectMapper,
            UsageReceivedProcessor usageReceivedProcessor,
            KafkaProperties kafkaProperties
    ) {
        this.objectMapper = objectMapper;
        this.usageReceivedProcessor = usageReceivedProcessor;
        this.kafkaProperties = kafkaProperties;
    }

    @KafkaListener(
            topics = "${usagecore.kafka.topics.usage-received}",
            groupId = "${usagecore.kafka.consumer-group}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope<UsageReceivedPayload> event;
        try {
            event = objectMapper.readValue(record.value(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.error(
                    "Failed to deserialize UsageReceived. topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    ex
            );
            throw new InvalidUsageEventException("Failed to deserialize UsageReceived event");
        }

        if (event == null) {
            throw new InvalidUsageEventException("Deserialized UsageReceived envelope was null");
        }

        log.debug(
                "Consuming UsageReceived. group={} topic={} partition={} offset={} key={} eventId={}",
                kafkaProperties.consumerGroup(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                event.eventId()
        );

        try {
            usageReceivedProcessor.process(event);
        } catch (UnsupportedUsageEventException | InvalidUsageEventException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error(
                    "Transient or unexpected UsageReceived processing failure. "
                            + "topic={} partition={} offset={} eventId={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    event.eventId(),
                    ex
            );
            throw ex;
        }
    }
}
