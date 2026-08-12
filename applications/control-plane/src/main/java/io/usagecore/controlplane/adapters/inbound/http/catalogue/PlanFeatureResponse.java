package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.PlanFeature;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import java.util.UUID;

public record PlanFeatureResponse(
        UUID featureId,
        EntitlementMode mode,
        Long maxQuantity
) {

    public static PlanFeatureResponse from(PlanFeature planFeature) {
        return new PlanFeatureResponse(
                planFeature.featureId(),
                planFeature.entitlementMode(),
                planFeature.limitConfiguration().map(limit -> limit.maxQuantity()).orElse(null)
        );
    }
}
