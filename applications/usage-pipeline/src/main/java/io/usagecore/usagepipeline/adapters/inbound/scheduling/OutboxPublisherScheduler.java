package io.usagecore.usagepipeline.adapters.inbound.scheduling;

import io.usagecore.usagepipeline.application.outbox.OutboxPublisherApplicationService;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import io.usagecore.usagepipeline.configuration.OutboxPublisherProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin scheduled adapter; core publish logic lives in {@link OutboxPublisherApplicationService}
 * so tests can invoke it without sleeps.
 */
@Component
@ConditionalOnProperty(prefix = "usagecore.outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final OutboxPublisherApplicationService outboxPublisherApplicationService;
    private final OutboxPublisherProperties properties;

    public OutboxPublisherScheduler(
            OutboxPublisherApplicationService outboxPublisherApplicationService,
            OutboxPublisherProperties properties
    ) {
        this.outboxPublisherApplicationService = outboxPublisherApplicationService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${usagecore.outbox.publisher.fixed-delay-ms:1000}")
    public void publishPending() {
        try {
            int published = outboxPublisherApplicationService.publishBatch(properties.batchSize());
            if (published > 0) {
                log.debug("Outbox publisher published {} event(s)", published);
            }
        } catch (UsagePublicationException ex) {
            log.warn("Outbox publisher batch failed; pending rows remain eligible for retry", ex);
        }
    }
}
