package io.usagecore.usagepipeline.application.usage;

import java.util.UUID;

public interface ProcessedEventRepository {

    /**
     * Claims {@code eventId} for processing.
     *
     * @return {@code true} if this call inserted the claim; {@code false} if already processed
     */
    boolean tryClaim(ProcessedEventRecord record);

    long countByEventId(UUID eventId);

    long countAll();
}
