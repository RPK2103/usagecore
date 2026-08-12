package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.DomainInvariantException;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractApplicationService {

    private final ContractRepository contractRepository;

    public ContractApplicationService(ContractRepository contractRepository) {
        this.contractRepository = contractRepository;
    }

    @Transactional
    public Contract createContract(Tenant tenant, Product product, BusinessKey contractKey) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(contractKey, "contractKey");
        if (contractRepository.existsByTenantIdAndProductId(tenant.id(), product.id())) {
            throw new DomainInvariantException("A contract already exists for this tenant and product");
        }
        if (contractRepository.existsByTenantIdAndContractKey(tenant.id(), contractKey.value())) {
            throw new DomainInvariantException("contractKey already exists for this tenant");
        }
        return contractRepository.save(Contract.create(tenant, product, contractKey));
    }

    @Transactional(readOnly = true)
    public Contract requireContract(UUID contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new DomainInvariantException("Contract not found"));
    }
}
