package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.ContractRepository;
import io.usagecore.controlplane.domain.catalogue.Contract;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ContractPersistenceAdapter implements ContractRepository {

    private final ContractJpaRepository contractJpaRepository;

    ContractPersistenceAdapter(ContractJpaRepository contractJpaRepository) {
        this.contractJpaRepository = contractJpaRepository;
    }

    @Override
    @Transactional
    public Contract save(Contract contract) {
        Instant now = Instant.now();
        Optional<ContractJpaEntity> existing = contractJpaRepository.findById(contract.id());
        if (existing.isPresent()) {
            ContractJpaEntity entity = existing.get();
            entity.setStatus(contract.status().name());
            entity.setUpdatedAt(now);
            contractJpaRepository.save(entity);
        } else {
            contractJpaRepository.save(new ContractJpaEntity(
                    contract.id(),
                    contract.tenantId(),
                    contract.productId(),
                    contract.contractKey().value(),
                    contract.status().name(),
                    now,
                    now
            ));
        }
        return contract;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> findById(UUID id) {
        return contractJpaRepository.findById(id).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> findByIdAndTenantId(UUID id, UUID tenantId) {
        return contractJpaRepository.findByIdAndTenantId(id, tenantId).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> findByTenantIdAndProductId(UUID tenantId, UUID productId) {
        return contractJpaRepository.findByTenantIdAndProductId(tenantId, productId)
                .map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> findByTenantIdAndContractKey(UUID tenantId, String contractKey) {
        return contractJpaRepository.findByTenantIdAndContractKey(tenantId, contractKey)
                .map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTenantIdAndProductId(UUID tenantId, UUID productId) {
        return contractJpaRepository.existsByTenantIdAndProductId(tenantId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTenantIdAndContractKey(UUID tenantId, String contractKey) {
        return contractJpaRepository.existsByTenantIdAndContractKey(tenantId, contractKey);
    }
}
