package io.usagecore.controlplane.domain.catalogue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reusable commercial template for a product. Not historical commercial truth
 * (see ADR-004). DRAFT configuration may change; PUBLISHED commercial
 * configuration must not be silently mutated; ARCHIVED is not deletion.
 */
public final class Plan {

    private final UUID id;
    private final UUID productId;
    private final BusinessKey planKey;
    private String name;
    private PlanStatus status;
    private final List<PlanFeature> planFeatures;

    private Plan(
            UUID id,
            UUID productId,
            BusinessKey planKey,
            String name,
            PlanStatus status,
            List<PlanFeature> planFeatures
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.planKey = Objects.requireNonNull(planKey, "planKey");
        this.name = DisplayNames.requireNonBlank(name, "name");
        this.status = Objects.requireNonNull(status, "status");
        this.planFeatures = new ArrayList<>(Objects.requireNonNull(planFeatures, "planFeatures"));
    }

    public static Plan createDraft(Product product, BusinessKey planKey, String name) {
        Objects.requireNonNull(product, "product");
        return new Plan(
                UUID.randomUUID(),
                product.id(),
                planKey,
                name,
                PlanStatus.DRAFT,
                new ArrayList<>()
        );
    }

    public static Plan reconstitute(
            UUID id,
            UUID productId,
            BusinessKey planKey,
            String name,
            PlanStatus status,
            List<PlanFeature> planFeatures
    ) {
        return new Plan(id, productId, planKey, name, status, planFeatures);
    }

    public void rename(String name) {
        assertMutable();
        this.name = DisplayNames.requireNonBlank(name, "name");
    }

    public PlanFeature addFeature(Feature feature, EntitlementMode mode, LimitConfiguration limit) {
        assertDraftCommercialMutation();
        Objects.requireNonNull(feature, "feature");
        feature.assertBelongsToProduct(productId);
        if (findFeature(feature.id()).isPresent()) {
            throw new DomainInvariantException("Plan already contains feature " + feature.featureKey());
        }
        PlanFeature planFeature = PlanFeature.create(id, feature, mode, limit);
        planFeatures.add(planFeature);
        return planFeature;
    }

    public void updateFeature(UUID featureId, EntitlementMode mode, LimitConfiguration limit) {
        assertDraftCommercialMutation();
        PlanFeature planFeature = requireFeature(featureId);
        planFeature.reconfigure(mode, limit);
    }

    public void removeFeature(UUID featureId) {
        assertDraftCommercialMutation();
        PlanFeature planFeature = requireFeature(featureId);
        planFeatures.remove(planFeature);
    }

    public void publish() {
        assertNotArchived();
        if (status != PlanStatus.DRAFT) {
            throw new DomainInvariantException("Only DRAFT plans can be published");
        }
        this.status = PlanStatus.PUBLISHED;
    }

    public void archive() {
        if (status == PlanStatus.ARCHIVED) {
            return;
        }
        this.status = PlanStatus.ARCHIVED;
    }

    private PlanFeature requireFeature(UUID featureId) {
        return findFeature(featureId)
                .orElseThrow(() -> new DomainInvariantException("Feature is not part of this plan"));
    }

    private Optional<PlanFeature> findFeature(UUID featureId) {
        return planFeatures.stream()
                .filter(planFeature -> planFeature.featureId().equals(featureId))
                .findFirst();
    }

    private void assertDraftCommercialMutation() {
        if (status == PlanStatus.PUBLISHED) {
            throw new DomainInvariantException("PUBLISHED plan commercial configuration cannot be mutated");
        }
        if (status == PlanStatus.ARCHIVED) {
            throw new DomainInvariantException("ARCHIVED plan cannot be modified");
        }
    }

    private void assertMutable() {
        if (status == PlanStatus.ARCHIVED) {
            throw new DomainInvariantException("ARCHIVED plan cannot be modified");
        }
        if (status == PlanStatus.PUBLISHED) {
            throw new DomainInvariantException("PUBLISHED plan commercial configuration cannot be mutated");
        }
    }

    private void assertNotArchived() {
        if (status == PlanStatus.ARCHIVED) {
            throw new DomainInvariantException("ARCHIVED plan cannot be modified");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID productId() {
        return productId;
    }

    public BusinessKey planKey() {
        return planKey;
    }

    public String name() {
        return name;
    }

    public PlanStatus status() {
        return status;
    }

    public List<PlanFeature> planFeatures() {
        return Collections.unmodifiableList(planFeatures);
    }
}
