package io.usagecore.usagepipeline.application.commercial;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Purpose-built commercial period resolution for usage/quota processing.
 * Empty means NO_PERIOD (Phase 6 compatibility — lifecycle enforcement inactive).
 */
public interface CommercialPeriodReader {

    /**
     * Resolves the commercial period covering {@code occurredAt} for tenant+product.
     * Acquires {@code FOR SHARE} so concurrent lifecycle transitions serialize with
     * usage mutation in the same transaction.
     * <p>
     * Zero rows → empty (NO_PERIOD). More than one row → invariant failure (overlap corruption).
     */
    Optional<CommercialPeriodView> findCoveringForShare(UUID tenantId, UUID productId, Instant occurredAt);
}
