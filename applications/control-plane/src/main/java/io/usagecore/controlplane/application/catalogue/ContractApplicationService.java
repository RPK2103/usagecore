package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractApplicationService {

    private final ContractRepository contractRepository;
    private final TenantRepository tenantRepository;
    private final ProductRepository productRepository;

    public ContractApplicationService(
            ContractRepository contractRepository,
            TenantRepository tenantRepository,
            ProductRepository productRepository
    ) {
        this.contractRepository = contractRepository;
        this.tenantRepository = tenantRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Contract createContract(UUID tenantId, UUID productId, BusinessKey contractKey) {
        Objects.requireNonNull(contractKey, "contractKey");
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        if (contractRepository.existsByTenantIdAndProductId(tenant.id(), product.id())) {
            throw new DuplicateResourceException("A contract already exists for this tenant and product");
        }
        if (contractRepository.existsByTenantIdAndContractKey(tenant.id(), contractKey.value())) {
            throw new DuplicateResourceException("contractKey already exists for this tenant");
        }
        return contractRepository.save(Contract.create(tenant, product, contractKey));
    }

    @Transactional(readOnly = true)
    public Contract requireContract(UUID contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract not found: " + contractId));
    }
}
