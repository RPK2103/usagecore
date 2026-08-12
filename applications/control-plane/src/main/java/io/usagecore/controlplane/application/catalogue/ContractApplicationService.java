package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.application.security.CurrentPrincipal;
import io.usagecore.controlplane.application.security.PlatformRole;
import io.usagecore.controlplane.application.security.TenantAccessGuard;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractApplicationService {

    private final ContractRepository contractRepository;
    private final TenantRepository tenantRepository;
    private final ProductRepository productRepository;
    private final CurrentPrincipal currentPrincipal;
    private final TenantAccessGuard tenantAccessGuard;

    public ContractApplicationService(
            ContractRepository contractRepository,
            TenantRepository tenantRepository,
            ProductRepository productRepository,
            CurrentPrincipal currentPrincipal,
            TenantAccessGuard tenantAccessGuard
    ) {
        this.contractRepository = contractRepository;
        this.tenantRepository = tenantRepository;
        this.productRepository = productRepository;
        this.currentPrincipal = currentPrincipal;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    @Transactional
    public Contract createContract(UUID tenantId, UUID productId, BusinessKey contractKey) {
        Objects.requireNonNull(contractKey, "contractKey");
        tenantAccessGuard.assertHasAnyRole(
                "CREATE_CONTRACT",
                "Contract",
                null,
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.CONTRACT_MANAGER
        );
        UUID effectiveTenantId = tenantAccessGuard.resolveWritableTenantId(tenantId, "CREATE_CONTRACT");
        Tenant tenant = tenantRepository.findById(effectiveTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + effectiveTenantId));
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
        tenantAccessGuard.assertHasAnyRole(
                "READ_CONTRACT",
                "Contract",
                contractId.toString(),
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.CONTRACT_MANAGER,
                PlatformRole.TENANT_ADMIN,
                PlatformRole.AUDITOR
        );
        return requireContractForCurrentCaller(contractId, "READ_CONTRACT");
    }

    /**
     * Loads a contract for mutation paths. Same tenant isolation as read, with mutate roles.
     */
    @Transactional(readOnly = true)
    public Contract requireContractForMutation(UUID contractId, String action) {
        tenantAccessGuard.assertHasAnyRole(
                action,
                "Contract",
                contractId.toString(),
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.CONTRACT_MANAGER
        );
        return requireContractForCurrentCaller(contractId, action);
    }

    private Contract requireContractForCurrentCaller(UUID contractId, String action) {
        var principal = currentPrincipal.require();
        if (principal.isPlatformAdmin()) {
            return contractRepository.findById(contractId)
                    .orElseThrow(() -> new ResourceNotFoundException("Contract not found: " + contractId));
        }
        UUID callerTenantId = principal.tenantId()
                .orElseThrow(() -> new ResourceNotFoundException("Contract not found: " + contractId));
        Optional<Contract> scoped = contractRepository.findByIdAndTenantId(contractId, callerTenantId);
        if (scoped.isPresent()) {
            return scoped.get();
        }
        Optional<Contract> any = contractRepository.findById(contractId);
        if (any.isPresent()) {
            tenantAccessGuard.assertCanAccessTenant(
                    any.get().tenantId(),
                    action,
                    "Contract",
                    contractId.toString()
            );
        }
        throw new ResourceNotFoundException("Contract not found: " + contractId);
    }
}
