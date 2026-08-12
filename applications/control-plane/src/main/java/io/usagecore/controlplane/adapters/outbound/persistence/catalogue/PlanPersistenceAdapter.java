package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.PlanRepository;
import io.usagecore.controlplane.domain.catalogue.Plan;
import io.usagecore.controlplane.domain.catalogue.PlanFeature;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PlanPersistenceAdapter implements PlanRepository {

    private final PlanJpaRepository planJpaRepository;
    private final PlanFeatureJpaRepository planFeatureJpaRepository;
    private final FeatureJpaRepository featureJpaRepository;

    PlanPersistenceAdapter(
            PlanJpaRepository planJpaRepository,
            PlanFeatureJpaRepository planFeatureJpaRepository,
            FeatureJpaRepository featureJpaRepository
    ) {
        this.planJpaRepository = planJpaRepository;
        this.planFeatureJpaRepository = planFeatureJpaRepository;
        this.featureJpaRepository = featureJpaRepository;
    }

    @Override
    @Transactional
    public Plan save(Plan plan) {
        Instant now = Instant.now();
        Optional<PlanJpaEntity> existing = planJpaRepository.findById(plan.id());
        if (existing.isPresent()) {
            PlanJpaEntity entity = existing.get();
            entity.setName(plan.name());
            entity.setStatus(plan.status().name());
            entity.setUpdatedAt(now);
            planJpaRepository.save(entity);
        } else {
            planJpaRepository.save(new PlanJpaEntity(
                    plan.id(),
                    plan.productId(),
                    plan.planKey().value(),
                    plan.name(),
                    plan.status().name(),
                    now,
                    now
            ));
        }
        syncPlanFeatures(plan, now);
        return plan;
    }

    private void syncPlanFeatures(Plan plan, Instant now) {
        List<PlanFeatureJpaEntity> existingFeatures = planFeatureJpaRepository.findByPlanId(plan.id());
        Map<UUID, PlanFeatureJpaEntity> existingById = existingFeatures.stream()
                .collect(Collectors.toMap(PlanFeatureJpaEntity::getId, Function.identity()));

        Set<UUID> retainedIds = new HashSet<>();
        for (PlanFeature planFeature : plan.planFeatures()) {
            retainedIds.add(planFeature.id());
            PlanFeatureJpaEntity entity = existingById.get(planFeature.id());
            Long limitQuantity = planFeature.limitConfiguration()
                    .map(limit -> limit.maxQuantity())
                    .orElse(null);
            if (entity == null) {
                planFeatureJpaRepository.save(new PlanFeatureJpaEntity(
                        planFeature.id(),
                        plan.id(),
                        planFeature.featureId(),
                        planFeature.entitlementMode().name(),
                        limitQuantity,
                        now,
                        now
                ));
            } else {
                entity.setEntitlementMode(planFeature.entitlementMode().name());
                entity.setLimitQuantity(limitQuantity);
                entity.setUpdatedAt(now);
                planFeatureJpaRepository.save(entity);
            }
        }

        for (PlanFeatureJpaEntity entity : existingFeatures) {
            if (!retainedIds.contains(entity.getId())) {
                planFeatureJpaRepository.delete(entity);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plan> findById(UUID id) {
        return planJpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plan> findByProductIdAndPlanKey(UUID productId, String planKey) {
        return planJpaRepository.findByProductIdAndPlanKey(productId, planKey).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByProductIdAndPlanKey(UUID productId, String planKey) {
        return planJpaRepository.existsByProductIdAndPlanKey(productId, planKey);
    }

    private Plan toDomain(PlanJpaEntity planEntity) {
        List<PlanFeatureJpaEntity> planFeatures = planFeatureJpaRepository.findByPlanId(planEntity.getId());
        Map<UUID, UUID> featureIdToProductId = new HashMap<>();
        for (PlanFeatureJpaEntity planFeature : planFeatures) {
            featureJpaRepository.findById(planFeature.getFeatureId()).ifPresent(feature ->
                    featureIdToProductId.put(feature.getId(), feature.getProductId())
            );
        }
        return CataloguePersistenceMapper.toDomain(planEntity, planFeatures, featureIdToProductId);
    }
}
