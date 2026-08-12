package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record CreateContractVersionFromPlanRequest(
        @NotNull UUID planId,
        @NotNull Instant effectiveFrom,
        Instant effectiveUntil
) {
}
