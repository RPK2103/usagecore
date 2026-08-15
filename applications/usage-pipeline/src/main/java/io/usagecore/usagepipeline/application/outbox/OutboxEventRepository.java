package io.usagecore.usagepipeline.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository {

    void insertPending(OutboxEventRecord record);

    /**
     * Claims pending rows with {@code FOR UPDATE SKIP LOCKED} for the current transaction.
     * Caller must publish and mark published before the transaction commits.
     */
    List<OutboxEventRecord> claimPending(int batchSize);

    void markPublished(UUID id, Instant publishedAt);

    Optional<OutboxEventRecord> findByEventId(UUID eventId);

    long countByStatus(OutboxStatus status);

    long countAll();

    /**
     * Oldest PENDING row {@code created_at}, if any. Used by scrape-time gauges only.
     */
    Optional<Instant> oldestPendingCreatedAt();
}
