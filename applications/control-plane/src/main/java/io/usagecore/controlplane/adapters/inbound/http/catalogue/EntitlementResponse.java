package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.Entitlement;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import java.util.UUID;

public record EntitlementResponse(
        UUID featureId,
        EntitlementMode mode,
        Long maxQuantity
) {

    public static EntitlementResponse from(Entitlement entitlement) {
        return new EntitlementResponse(
                entitlement.featureId(),
                entitlement.entitlementMode(),
                entitlement.limitConfiguration().map(limit -> limit.maxQuantity()).orElse(null)
        );
    }
}
