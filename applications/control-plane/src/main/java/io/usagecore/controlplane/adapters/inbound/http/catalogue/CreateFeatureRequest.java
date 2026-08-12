package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotBlank;

public record CreateFeatureRequest(
        @NotBlank String featureKey,
        @NotBlank String name
) {
}
