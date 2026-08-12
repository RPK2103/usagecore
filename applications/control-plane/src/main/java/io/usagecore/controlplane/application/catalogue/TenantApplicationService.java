package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantApplicationService {

    private final TenantRepository tenantRepository;

    public TenantApplicationService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant createTenant(BusinessKey tenantKey, String displayName) {
        Objects.requireNonNull(tenantKey, "tenantKey");
        if (tenantRepository.existsByTenantKey(tenantKey.value())) {
            throw new DuplicateResourceException("tenantKey already exists: " + tenantKey.value());
        }
        return tenantRepository.save(Tenant.create(tenantKey, displayName));
    }

    @Transactional(readOnly = true)
    public Tenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
    }
}
