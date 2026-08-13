package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.CommercialPeriod;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriodStatus;
import io.usagecore.controlplane.domain.catalogue.CommercialPeriodTransition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommercialPeriodRepository {

    CommercialPeriod saveNew(CommercialPeriod period);

    Optional<CommercialPeriod> findById(UUID id);

    Optional<CommercialPeriod> findByIdAndTenantIdAndProductId(UUID id, UUID tenantId, UUID productId);

    /**
     * Atomically transitions status when current status matches {@code fromStatus}.
     * Empty means invalid/already transitioned — DB is concurrency authority.
     */
    Optional<CommercialPeriod> transitionIfStatus(
            UUID id,
            CommercialPeriodStatus fromStatus,
            CommercialPeriodStatus toStatus,
            Instant transitionedAt,
            String finalizedByOrNull
    );

    void appendTransition(CommercialPeriodTransition transition);

    List<CommercialPeriodTransition> findTransitionsByPeriodId(UUID commercialPeriodId);
}
