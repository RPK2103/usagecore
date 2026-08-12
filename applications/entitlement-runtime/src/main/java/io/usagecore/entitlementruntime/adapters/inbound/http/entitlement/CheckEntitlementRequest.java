package io.usagecore.entitlementruntime.adapters.inbound.http.entitlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Entitlement check request. tenantId is intentionally absent — unknown fields are rejected.
 * Workspace is not part of the current domain.
 */
public record CheckEntitlementRequest(
        @NotBlank String productKey,
        @NotBlank String featureKey,
        @Positive Long requestedUnits
) {
}
