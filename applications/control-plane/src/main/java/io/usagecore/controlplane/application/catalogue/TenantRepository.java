package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(UUID id);

    Optional<Tenant> findByTenantKey(String tenantKey);

    boolean existsByTenantKey(String tenantKey);
}
