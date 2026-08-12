package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.ContractStatus;
import java.util.UUID;

public record ContractResponse(
        UUID id,
        UUID tenantId,
        UUID productId,
        String contractKey,
        ContractStatus status
) {

    public static ContractResponse from(Contract contract) {
        return new ContractResponse(
                contract.id(),
                contract.tenantId(),
                contract.productId(),
                contract.contractKey().value(),
                contract.status()
        );
    }
}
