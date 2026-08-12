package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Feature membership on a plan template with an entitlement concept.
 */
public final class PlanFeature {

    private final UUID id;
    private final UUID planId;
    private final UUID featureId;
    private final UUID featureProductId;
    private EntitlementMode entitlementMode;
    private LimitConfiguration limitConfiguration;

    private PlanFeature(
            UUID id,
            UUID planId,
            UUID featureId,
            UUID featureProductId,
            EntitlementMode entitlementMode,
            LimitConfiguration limitConfiguration
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.featureId = Objects.requireNonNull(featureId, "featureId");
        this.featureProductId = Objects.requireNonNull(featureProductId, "featureProductId");
        applyConfiguration(entitlementMode, limitConfiguration);
    }

    static PlanFeature create(UUID planId, Feature feature, EntitlementMode mode, LimitConfiguration limit) {
        Objects.requireNonNull(feature, "feature");
        return new PlanFeature(
                UUID.randomUUID(),
                planId,
                feature.id(),
                feature.productId(),
                mode,
                limit
        );
    }

    public static PlanFeature reconstitute(
            UUID id,
            UUID planId,
            UUID featureId,
            UUID featureProductId,
            EntitlementMode entitlementMode,
            LimitConfiguration limitConfiguration
    ) {
        return new PlanFeature(id, planId, featureId, featureProductId, entitlementMode, limitConfiguration);
    }

    void reconfigure(EntitlementMode mode, LimitConfiguration limit) {
        applyConfiguration(mode, limit);
    }

    private void applyConfiguration(EntitlementMode mode, LimitConfiguration limit) {
        Objects.requireNonNull(mode, "entitlementMode");
        if (mode == EntitlementMode.LIMITED) {
            if (limit == null) {
                throw new DomainInvariantException("LIMITED requires a valid positive limit configuration");
            }
            this.entitlementMode = mode;
            this.limitConfiguration = limit;
            return;
        }
        if (limit != null) {
            throw new DomainInvariantException(mode + " entitlement must not carry a limit configuration");
        }
        this.entitlementMode = mode;
        this.limitConfiguration = null;
    }

    public UUID id() {
        return id;
    }

    public UUID planId() {
        return planId;
    }

    public UUID featureId() {
        return featureId;
    }

    public UUID featureProductId() {
        return featureProductId;
    }

    public EntitlementMode entitlementMode() {
        return entitlementMode;
    }

    public Optional<LimitConfiguration> limitConfiguration() {
        return Optional.ofNullable(limitConfiguration);
    }
}
