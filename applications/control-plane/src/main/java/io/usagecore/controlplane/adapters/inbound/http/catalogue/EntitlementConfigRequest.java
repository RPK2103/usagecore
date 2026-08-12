package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import io.usagecore.controlplane.domain.catalogue.LimitConfiguration;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Shared entitlement configuration payload for plan features and contract entitlements.
 */
public record EntitlementConfigRequest(
        @NotNull EntitlementMode mode,
        @Positive Long maxQuantity
) {

    public LimitConfiguration toLimitConfiguration() {
        if (mode == EntitlementMode.LIMITED) {
            if (maxQuantity == null) {
                throw new IllegalArgumentException("LIMITED requires maxQuantity");
            }
            return LimitConfiguration.ofMaxQuantity(maxQuantity);
        }
        if (maxQuantity != null) {
            throw new IllegalArgumentException(mode + " must not include maxQuantity");
        }
        return null;
    }
}
