package io.usagecore.usagepipeline.application.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.usagepipeline.application.usage.UsageEventPublisher;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes PENDING outbox rows to Kafka, then marks them PUBLISHED after acknowledgement.
 * Holds row locks ({@code FOR UPDATE SKIP LOCKED}) for the duration of Kafka publish —
 * simple and correct for v1; high lock duration under slow brokers is an accepted trade-off.
 * <p>
 * At-least-once: if Kafka accepts the message and the process dies before PUBLISHED commit,
 * the row stays PENDING and may be published again with the same eventId.
 */
@Service
public class OutboxPublisherApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherApplicationService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final UsageEventPublisher usageEventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPublisherApplicationService(
            OutboxEventRepository outboxEventRepository,
            UsageEventPublisher usageEventPublisher,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.usageEventPublisher = usageEventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Claims up to {@code batchSize} PENDING rows, publishes each stored envelope, marks PUBLISHED.
     * On Kafka failure the transaction rolls back and rows remain PENDING for retry.
     *
     * @return number of rows successfully published in this batch
     */
    @Transactional
    public int publishBatch(int batchSize) {
        if (batchSize <= 0) {
            return 0;
        }

        List<OutboxEventRecord> claimed = outboxEventRepository.claimPending(batchSize);
        int published = 0;
        for (OutboxEventRecord row : claimed) {
            try {
                usageEventPublisher.publishSerialized(
                        row.topic(),
                        row.partitionKey(),
                        row.serializedEnvelope(),
                        row.eventId().toString(),
                        row.eventType(),
                        row.eventVersion(),
                        correlationIdFromEnvelope(row.serializedEnvelope())
                );
            } catch (UsagePublicationException ex) {
                log.warn(
                        "Outbox publication failed for eventId={}; leaving PENDING for retry",
                        row.eventId(),
                        ex
                );
                throw ex;
            }
            outboxEventRepository.markPublished(row.id(), clock.instant());
            published++;
        }
        return published;
    }

    private String correlationIdFromEnvelope(String serializedEnvelope) {
        try {
            JsonNode node = objectMapper.readTree(serializedEnvelope);
            JsonNode correlation = node.get("correlationId");
            return correlation != null && !correlation.isNull() ? correlation.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
