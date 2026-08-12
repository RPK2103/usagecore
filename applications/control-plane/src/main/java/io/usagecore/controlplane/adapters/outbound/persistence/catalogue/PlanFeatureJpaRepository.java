package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanFeatureJpaRepository extends JpaRepository<PlanFeatureJpaEntity, UUID> {

    List<PlanFeatureJpaEntity> findByPlanId(UUID planId);

    void deleteByPlanId(UUID planId);
}
