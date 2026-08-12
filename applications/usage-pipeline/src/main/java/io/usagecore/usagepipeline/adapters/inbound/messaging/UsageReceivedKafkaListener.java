package io.usagecore.usagepipeline.adapters.inbound.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.events.EventEnvelope;
import io.usagecore.events.usage.UsageReceivedPayload;
import io.usagecore.usagepipeline.application.usage.UnsupportedUsageEventException;
import io.usagecore.usagepipeline.application.usage.UsageReceivedProcessor;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 4 consumer foundation for {@code usagecore.usage.received.v1}.
 * Deserializes and validates supported contracts; does not perform commercial side effects.
 * Not claimed idempotent — Phase 5 adds inbox/deduplication.
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
                    "Failed to deserialize UsageReceived (Phase 4). topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    ex
            );
            throw new UnsupportedUsageEventException("Failed to deserialize UsageReceived event");
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

        usageReceivedProcessor.process(event);
    }
}
