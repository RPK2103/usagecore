package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
        @NotBlank String tenantKey,
        @NotBlank String displayName
) {
}
