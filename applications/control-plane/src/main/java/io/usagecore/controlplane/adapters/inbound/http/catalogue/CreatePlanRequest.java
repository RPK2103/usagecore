package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotBlank;

public record CreatePlanRequest(
        @NotBlank String planKey,
        @NotBlank String name
) {
}
