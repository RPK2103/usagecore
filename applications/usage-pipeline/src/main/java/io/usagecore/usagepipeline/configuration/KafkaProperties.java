package io.usagecore.usagepipeline.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usagecore.kafka")
public record KafkaProperties(
        Topics topics,
        String consumerGroup,
        Duration publishTimeout,
        ConsumerRetry consumerRetry
) {

    public KafkaProperties {
        if (consumerRetry == null) {
            consumerRetry = new ConsumerRetry(200L, 3L);
        }
    }

    public record Topics(String usageReceived, String usageReceivedDlq) {
    }

    /**
     * Bounded listener retries before recover/DLQ.
     *
     * @param intervalMs pause between attempts
     * @param maxAttempts total delivery attempts including the first (minimum 1)
     */
    public record ConsumerRetry(long intervalMs, long maxAttempts) {
    }
}
