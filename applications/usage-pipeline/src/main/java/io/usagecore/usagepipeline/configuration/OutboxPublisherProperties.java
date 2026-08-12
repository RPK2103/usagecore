package io.usagecore.usagepipeline.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usagecore.outbox.publisher")
public record OutboxPublisherProperties(
        boolean enabled,
        int batchSize,
        long fixedDelayMs
) {
    public OutboxPublisherProperties {
        if (batchSize <= 0) {
            batchSize = 50;
        }
        if (fixedDelayMs <= 0) {
            fixedDelayMs = 1000L;
        }
    }
}
