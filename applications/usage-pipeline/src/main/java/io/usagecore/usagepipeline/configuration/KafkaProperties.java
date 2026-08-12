package io.usagecore.usagepipeline.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usagecore.kafka")
public record KafkaProperties(
        Topics topics,
        String consumerGroup,
        Duration publishTimeout
) {

    public record Topics(String usageReceived) {
    }
}
