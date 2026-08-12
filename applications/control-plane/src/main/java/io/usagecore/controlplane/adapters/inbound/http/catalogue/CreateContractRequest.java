package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateContractRequest(
        @NotNull UUID tenantId,
        @NotNull UUID productId,
        @NotBlank String contractKey
) {
}
