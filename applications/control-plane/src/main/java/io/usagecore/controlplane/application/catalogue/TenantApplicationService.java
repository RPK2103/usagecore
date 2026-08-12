package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.application.security.CurrentPrincipal;
import io.usagecore.controlplane.application.security.PlatformRole;
import io.usagecore.controlplane.application.security.TenantAccessGuard;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantApplicationService {

    private final TenantRepository tenantRepository;
    private final CurrentPrincipal currentPrincipal;
    private final TenantAccessGuard tenantAccessGuard;

    public TenantApplicationService(
            TenantRepository tenantRepository,
            CurrentPrincipal currentPrincipal,
            TenantAccessGuard tenantAccessGuard
    ) {
        this.tenantRepository = tenantRepository;
        this.currentPrincipal = currentPrincipal;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    @Transactional
    public Tenant createTenant(BusinessKey tenantKey, String displayName) {
        Objects.requireNonNull(tenantKey, "tenantKey");
        tenantAccessGuard.assertHasAnyRole(
                "CREATE_TENANT",
                "Tenant",
                null,
                PlatformRole.PLATFORM_ADMIN
        );
        if (tenantRepository.existsByTenantKey(tenantKey.value())) {
            throw new DuplicateResourceException("tenantKey already exists: " + tenantKey.value());
        }
        return tenantRepository.save(Tenant.create(tenantKey, displayName));
    }

    @Transactional(readOnly = true)
    public Tenant requireTenant(UUID tenantId) {
        tenantAccessGuard.assertHasAnyRole(
                "READ_TENANT",
                "Tenant",
                tenantId.toString(),
                PlatformRole.PLATFORM_ADMIN,
                PlatformRole.CONTRACT_MANAGER,
                PlatformRole.TENANT_ADMIN,
                PlatformRole.AUDITOR
        );
        var principal = currentPrincipal.require();
        if (!principal.isPlatformAdmin()) {
            Optional<Tenant> existing = tenantRepository.findById(tenantId);
            if (existing.isPresent()) {
                tenantAccessGuard.assertCanAccessTenant(tenantId, "READ_TENANT", "Tenant", tenantId.toString());
                return existing.get();
            }
            throw new ResourceNotFoundException("Tenant not found: " + tenantId);
        }
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
    }
}
