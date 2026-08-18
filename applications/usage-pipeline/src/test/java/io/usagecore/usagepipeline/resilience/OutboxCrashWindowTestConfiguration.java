package io.usagecore.usagepipeline.resilience;

import io.usagecore.usagepipeline.adapters.observability.OutboxPublishSpanSupport;
import io.usagecore.usagepipeline.adapters.outbound.messaging.SpringKafkaUsageEventPublisher;
import io.usagecore.usagepipeline.application.usage.UsageEventPublisher;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import io.usagecore.usagepipeline.configuration.KafkaProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only publisher wrapper. Production {@link SpringKafkaUsageEventPublisher} still performs the send.
 */
@TestConfiguration
class OutboxCrashWindowTestConfiguration {

    enum Mode {
        PASS,
        FAIL_BEFORE_SEND,
        FAIL_AFTER_ACK
    }

    static final class Gate {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.PASS);
    }

    @Bean
    Gate outboxCrashGate() {
        return new Gate();
    }

    @Bean
    @Primary
    UsageEventPublisher gatedPublisher(
            Gate gate,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaProperties kafkaProperties,
            OutboxPublishSpanSupport outboxPublishSpanSupport,
            ObjectMapper objectMapper
    ) {
        UsageEventPublisher delegate = new SpringKafkaUsageEventPublisher(
                kafkaTemplate,
                kafkaProperties,
                outboxPublishSpanSupport,
                objectMapper
        );
        return (
                topic,
                partitionKey,
                serializedEnvelope,
                eventId,
                eventType,
                eventVersion,
                correlationId
        ) -> {
            if (gate.mode.get() == Mode.FAIL_BEFORE_SEND) {
                throw new UsagePublicationException("test: failure before Kafka send");
            }
            delegate.publishSerialized(
                    topic,
                    partitionKey,
                    serializedEnvelope,
                    eventId,
                    eventType,
                    eventVersion,
                    correlationId
            );
            if (gate.mode.get() == Mode.FAIL_AFTER_ACK) {
                throw new UsagePublicationException("test: failure after Kafka ACK before PUBLISHED");
            }
        };
    }
}
