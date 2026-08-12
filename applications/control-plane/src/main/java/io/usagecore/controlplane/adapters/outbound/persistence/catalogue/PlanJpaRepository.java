package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanJpaRepository extends JpaRepository<PlanJpaEntity, UUID> {

    Optional<PlanJpaEntity> findByProductIdAndPlanKey(UUID productId, String planKey);

    boolean existsByProductIdAndPlanKey(UUID productId, String planKey);
}
