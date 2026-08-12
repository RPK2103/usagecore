package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot of feature rights bound to a {@link ContractVersion}. Not an aggregate root.
 */
public final class Entitlement {

    private final UUID id;
    private final UUID contractVersionId;
    private final UUID featureId;
    private final UUID featureProductId;
    private EntitlementMode entitlementMode;
    private LimitConfiguration limitConfiguration;

    private Entitlement(
            UUID id,
            UUID contractVersionId,
            UUID featureId,
            UUID featureProductId,
            EntitlementMode entitlementMode,
            LimitConfiguration limitConfiguration
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.contractVersionId = Objects.requireNonNull(contractVersionId, "contractVersionId");
        this.featureId = Objects.requireNonNull(featureId, "featureId");
        this.featureProductId = Objects.requireNonNull(featureProductId, "featureProductId");
        applyConfiguration(entitlementMode, limitConfiguration);
    }

    static Entitlement create(UUID contractVersionId, Feature feature, EntitlementMode mode, LimitConfiguration limit) {
        Objects.requireNonNull(feature, "feature");
        return new Entitlement(
                UUID.randomUUID(),
                contractVersionId,
                feature.id(),
                feature.productId(),
                mode,
                limit
        );
    }

    static Entitlement fromPlanFeature(UUID contractVersionId, UUID contractProductId, PlanFeature planFeature) {
        Objects.requireNonNull(planFeature, "planFeature");
        if (!planFeature.featureProductId().equals(contractProductId)) {
            throw new DomainInvariantException("Plan feature does not belong to the contract product");
        }
        return new Entitlement(
                UUID.randomUUID(),
                contractVersionId,
                planFeature.featureId(),
                planFeature.featureProductId(),
                planFeature.entitlementMode(),
                planFeature.limitConfiguration().orElse(null)
        );
    }

    public static Entitlement reconstitute(
            UUID id,
            UUID contractVersionId,
            UUID featureId,
            UUID featureProductId,
            EntitlementMode entitlementMode,
            LimitConfiguration limitConfiguration
    ) {
        return new Entitlement(
                id,
                contractVersionId,
                featureId,
                featureProductId,
                entitlementMode,
                limitConfiguration
        );
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

    public UUID contractVersionId() {
        return contractVersionId;
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
