package io.usagecore.usagepipeline.application.usage;

import io.usagecore.events.EventEnvelope;
import io.usagecore.events.EventTypes;
import io.usagecore.events.EventVersions;
import io.usagecore.events.usage.UsageReceivedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 4 consumer handler: validates supported eventType/eventVersion and logs receipt.
 * Does not update usage totals, consume quota, or claim idempotent processing.
 */
@Service
public class LoggingUsageReceivedProcessor implements UsageReceivedProcessor {

    private static final Logger log = LoggerFactory.getLogger(LoggingUsageReceivedProcessor.class);

    @Override
    public void process(EventEnvelope<UsageReceivedPayload> event) {
        validateSupportedContract(event);
        UsageReceivedPayload payload = event.payload();
        log.info(
                "UsageReceived observed (Phase 4 — no commercial effects). eventId={} tenantId={} "
                        + "productKey={} meterKey={} quantity={} idempotencyKey={} correlationId={} "
                        + "occurredAt={} publishedAt={}",
                event.eventId(),
                event.tenantId(),
                payload.productKey(),
                payload.meterKey(),
                payload.quantity(),
                payload.idempotencyKey(),
                event.correlationId(),
                event.occurredAt(),
                event.publishedAt()
        );
    }

    public static void validateSupportedContract(EventEnvelope<UsageReceivedPayload> event) {
        if (!EventTypes.USAGE_RECEIVED.equals(event.eventType())) {
            throw new UnsupportedUsageEventException(
                    "Unsupported eventType: " + event.eventType()
            );
        }
        if (!EventVersions.V1.equals(event.eventVersion())) {
            throw new UnsupportedUsageEventException(
                    "Unsupported eventVersion: " + event.eventVersion()
            );
        }
    }
}
