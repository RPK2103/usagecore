package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plan_feature")
class PlanFeatureJpaEntity {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "feature_id", nullable = false)
    private UUID featureId;

    @Column(name = "entitlement_mode", nullable = false, length = 32)
    private String entitlementMode;

    @Column(name = "limit_quantity")
    private Long limitQuantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanFeatureJpaEntity() {
    }

    PlanFeatureJpaEntity(
            UUID id,
            UUID planId,
            UUID featureId,
            String entitlementMode,
            Long limitQuantity,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.planId = planId;
        this.featureId = featureId;
        this.entitlementMode = entitlementMode;
        this.limitQuantity = limitQuantity;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getFeatureId() {
        return featureId;
    }

    String getEntitlementMode() {
        return entitlementMode;
    }

    Long getLimitQuantity() {
        return limitQuantity;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setEntitlementMode(String entitlementMode) {
        this.entitlementMode = entitlementMode;
    }

    void setLimitQuantity(Long limitQuantity) {
        this.limitQuantity = limitQuantity;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
