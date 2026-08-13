package io.usagecore.usagepipeline.application.commercial;

import java.util.Optional;
import java.util.UUID;

public interface CommercialUsageExceptionRepository {

    /**
     * Inserts quarantine evidence. Returns empty if {@code event_id} already exists
     * (duplicate redelivery safe).
     */
    Optional<UUID> insertIfAbsent(CommercialUsageExceptionRecord record);

    long countByEventId(UUID eventId);

    long countAll();
}
