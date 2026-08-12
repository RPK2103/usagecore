package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotBlank;

public record CreateProductRequest(
        @NotBlank String productKey,
        @NotBlank String name
) {
}
