package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ContractJpaRepository extends JpaRepository<ContractJpaEntity, UUID> {

    Optional<ContractJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ContractJpaEntity> findByTenantIdAndProductId(UUID tenantId, UUID productId);

    Optional<ContractJpaEntity> findByTenantIdAndContractKey(UUID tenantId, String contractKey);

    boolean existsByTenantIdAndProductId(UUID tenantId, UUID productId);

    boolean existsByTenantIdAndContractKey(UUID tenantId, String contractKey);
}
