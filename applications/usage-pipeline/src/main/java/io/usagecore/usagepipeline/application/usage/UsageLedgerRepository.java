package io.usagecore.usagepipeline.application.usage;

import java.util.Optional;
import java.util.UUID;

public interface UsageLedgerRepository {

    void insert(UsageLedgerRecord record);

    Optional<UsageLedgerRecord> findByEventId(UUID eventId);

    long countByEventId(UUID eventId);

    long countAll();
}
