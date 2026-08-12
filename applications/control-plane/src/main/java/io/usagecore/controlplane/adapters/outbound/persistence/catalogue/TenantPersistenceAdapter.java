package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.TenantRepository;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TenantPersistenceAdapter implements TenantRepository {

    private final TenantJpaRepository tenantJpaRepository;

    TenantPersistenceAdapter(TenantJpaRepository tenantJpaRepository) {
        this.tenantJpaRepository = tenantJpaRepository;
    }

    @Override
    @Transactional
    public Tenant save(Tenant tenant) {
        Instant now = Instant.now();
        Optional<TenantJpaEntity> existing = tenantJpaRepository.findById(tenant.id());
        if (existing.isPresent()) {
            TenantJpaEntity entity = existing.get();
            entity.setDisplayName(tenant.displayName());
            entity.setStatus(tenant.status().name());
            entity.setUpdatedAt(now);
            tenantJpaRepository.save(entity);
        } else {
            tenantJpaRepository.save(new TenantJpaEntity(
                    tenant.id(),
                    tenant.tenantKey().value(),
                    tenant.displayName(),
                    tenant.status().name(),
                    now,
                    now
            ));
        }
        return tenant;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findById(UUID id) {
        return tenantJpaRepository.findById(id).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findByTenantKey(String tenantKey) {
        return tenantJpaRepository.findByTenantKey(tenantKey).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTenantKey(String tenantKey) {
        return tenantJpaRepository.existsByTenantKey(tenantKey);
    }
}
