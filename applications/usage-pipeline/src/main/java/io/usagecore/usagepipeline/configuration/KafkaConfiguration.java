package io.usagecore.usagepipeline.configuration;

import io.usagecore.usagepipeline.application.observability.UsagePipelineMetrics;
import io.usagecore.usagepipeline.application.usage.InvalidUsageEventException;
import io.usagecore.usagepipeline.application.usage.UnknownUsageMeterException;
import io.usagecore.usagepipeline.application.usage.UnsupportedUsageEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and bounded consumer error handling for Phase 5B.
 * <p>
 * Ack mode remains {@code record} with auto-commit disabled: offsets are committed only
 * after the listener returns successfully (post DB commit). Non-retryable poison events
 * go to the DLQ after classification; transient failures retry a bounded number of times
 * then recover to DLQ. This is not production-grade poison-message recovery.
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
    NewTopic usageReceivedDlqTopic(KafkaProperties kafkaProperties) {
        return TopicBuilder.name(kafkaProperties.topics().usageReceivedDlq())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            KafkaProperties kafkaProperties,
            UsagePipelineMetrics metrics
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(kafkaProperties.topics().usageReceivedDlq(), record.partition())
        );

        ConsumerRecordRecoverer instrumented = (ConsumerRecord<?, ?> record, Exception ex) -> {
            String reason = nonRetryable(ex) ? "non_retryable" : "retry_exhausted";
            try {
                recoverer.accept(record, ex);
                metrics.recordDlq(reason);
            } catch (RuntimeException publishEx) {
                metrics.recordDlq("recoverer_failure");
                throw publishEx;
            }
        };

        long maxAttempts = Math.max(1L, kafkaProperties.consumerRetry().maxAttempts());
        long retriesAfterFirst = Math.max(0L, maxAttempts - 1L);
        FixedBackOff backOff = new FixedBackOff(
                Math.max(0L, kafkaProperties.consumerRetry().intervalMs()),
                retriesAfterFirst
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(instrumented, backOff);
        errorHandler.addNotRetryableExceptions(
                UnsupportedUsageEventException.class,
                InvalidUsageEventException.class,
                UnknownUsageMeterException.class
        );
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn(
                        "Kafka consumer delivery attempt failed. topic={} partition={} offset={} "
                                + "attempt={} error={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        deliveryAttempt,
                        ex.toString()
                )
        );
        return errorHandler;
    }

    private static boolean nonRetryable(Exception ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof UnsupportedUsageEventException
                    || current instanceof InvalidUsageEventException
                    || current instanceof UnknownUsageMeterException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
