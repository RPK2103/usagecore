package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateContractVersionRequest(
        @NotNull Instant effectiveFrom,
        Instant effectiveUntil
) {
}
