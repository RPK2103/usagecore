package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import io.usagecore.controlplane.domain.catalogue.ContractVersionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContractVersionResponse(
        UUID id,
        UUID contractId,
        int versionNumber,
        ContractVersionStatus status,
        Instant effectiveFrom,
        Instant effectiveUntil,
        Instant activatedAt,
        UUID sourcePlanId,
        List<EntitlementResponse> entitlements
) {

    public static ContractVersionResponse from(ContractVersion version) {
        return new ContractVersionResponse(
                version.id(),
                version.contractId(),
                version.versionNumber(),
                version.status(),
                version.effectiveFrom(),
                version.effectiveUntil(),
                version.activatedAt().orElse(null),
                version.sourcePlanId().orElse(null),
                version.entitlements().stream().map(EntitlementResponse::from).toList()
        );
    }
}
