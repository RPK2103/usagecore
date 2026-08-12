package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.Plan;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository {

    Plan save(Plan plan);

    Optional<Plan> findById(UUID id);

    Optional<Plan> findByProductIdAndPlanKey(UUID productId, String planKey);

    boolean existsByProductIdAndPlanKey(UUID productId, String planKey);
}
