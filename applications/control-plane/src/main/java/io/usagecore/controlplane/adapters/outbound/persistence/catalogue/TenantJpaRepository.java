package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, UUID> {

    Optional<TenantJpaEntity> findByTenantKey(String tenantKey);

    boolean existsByTenantKey(String tenantKey);
}
