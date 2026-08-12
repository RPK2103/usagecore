package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.Contract;
import java.util.Optional;
import java.util.UUID;

public interface ContractRepository {

    Contract save(Contract contract);

    Optional<Contract> findById(UUID id);

    Optional<Contract> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Contract> findByTenantIdAndProductId(UUID tenantId, UUID productId);

    Optional<Contract> findByTenantIdAndContractKey(UUID tenantId, String contractKey);

    boolean existsByTenantIdAndProductId(UUID tenantId, UUID productId);

    boolean existsByTenantIdAndContractKey(UUID tenantId, String contractKey);
}
