package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommercialPeriodJpaRepository extends JpaRepository<CommercialPeriodJpaEntity, UUID> {

    Optional<CommercialPeriodJpaEntity> findByIdAndTenantIdAndProductId(UUID id, UUID tenantId, UUID productId);
}
