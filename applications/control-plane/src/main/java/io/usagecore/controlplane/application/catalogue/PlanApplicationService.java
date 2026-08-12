package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.LimitConfiguration;
import io.usagecore.controlplane.domain.catalogue.Plan;
import io.usagecore.controlplane.domain.catalogue.Product;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanApplicationService {

    private final ProductRepository productRepository;
    private final PlanRepository planRepository;
    private final FeatureRepository featureRepository;

    public PlanApplicationService(
            ProductRepository productRepository,
            PlanRepository planRepository,
            FeatureRepository featureRepository
    ) {
        this.productRepository = productRepository;
        this.planRepository = planRepository;
        this.featureRepository = featureRepository;
    }

    @Transactional
    public Plan createDraftPlan(UUID productId, BusinessKey planKey, String name) {
        Objects.requireNonNull(planKey, "planKey");
        Product product = requireProduct(productId);
        if (planRepository.existsByProductIdAndPlanKey(product.id(), planKey.value())) {
            throw new DuplicateResourceException("planKey already exists for product: " + planKey.value());
        }
        return planRepository.save(Plan.createDraft(product, planKey, name));
    }

    @Transactional(readOnly = true)
    public Plan requirePlanForProduct(UUID productId, UUID planId) {
        requireProduct(productId);
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + planId));
        if (!plan.productId().equals(productId)) {
            throw new ResourceNotFoundException("Plan " + planId + " not found for product " + productId);
        }
        return plan;
    }

    @Transactional
    public Plan configurePlanFeature(
            UUID productId,
            UUID planId,
            UUID featureId,
            EntitlementMode mode,
            LimitConfiguration limit
    ) {
        Plan plan = requirePlanForProduct(productId, planId);
        Feature feature = featureRepository.findById(featureId)
                .orElseThrow(() -> new ResourceNotFoundException("Feature not found: " + featureId));
        boolean alreadyConfigured = plan.planFeatures().stream()
                .anyMatch(planFeature -> planFeature.featureId().equals(featureId));
        if (alreadyConfigured) {
            plan.updateFeature(featureId, mode, limit);
        } else {
            plan.addFeature(feature, mode, limit);
        }
        return planRepository.save(plan);
    }

    @Transactional
    public Plan publishPlan(UUID productId, UUID planId) {
        Plan plan = requirePlanForProduct(productId, planId);
        plan.publish();
        return planRepository.save(plan);
    }

    private Product requireProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
    }
}
