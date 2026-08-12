package io.usagecore.usagepipeline.configuration;

import io.usagecore.usagepipeline.application.usage.UnsupportedUsageEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka foundation for Phase 4.
 * <p>
 * Consumer group semantics: partitions are assigned among consumers in one group;
 * one partition is processed by at most one consumer in that group at a time;
 * consumer count beyond partition count does not increase useful parallelism.
 * There is no global ordering claim.
 * <p>
 * Retry/DLQ architecture is deferred to Phase 5. Phase 4 fails clearly without
 * building a full poison-message strategy.
 */
@Configuration
public class KafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    @Bean
    NewTopic usageReceivedTopic(KafkaProperties kafkaProperties) {
        return TopicBuilder.name(kafkaProperties.topics().usageReceived())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        // No retries in Phase 4 — fail clearly; Phase 5 designs retry/inbox/DLQ deliberately.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(0L, 0L));
        errorHandler.addNotRetryableExceptions(UnsupportedUsageEventException.class);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.error(
                        "Kafka consumer failure (Phase 4 — no DLQ). topic={} partition={} offset={} attempt={} error={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        deliveryAttempt,
                        ex.toString()
                )
        );
        return errorHandler;
    }
}
