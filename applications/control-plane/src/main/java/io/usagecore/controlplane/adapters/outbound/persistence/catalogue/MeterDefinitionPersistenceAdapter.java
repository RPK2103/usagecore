package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.MeterDefinitionRepository;
import io.usagecore.controlplane.domain.catalogue.MeterDefinition;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class MeterDefinitionPersistenceAdapter implements MeterDefinitionRepository {

    private final MeterDefinitionJpaRepository meterDefinitionJpaRepository;

    MeterDefinitionPersistenceAdapter(MeterDefinitionJpaRepository meterDefinitionJpaRepository) {
        this.meterDefinitionJpaRepository = meterDefinitionJpaRepository;
    }

    @Override
    @Transactional
    public MeterDefinition save(MeterDefinition meterDefinition) {
        Instant now = Instant.now();
        Optional<MeterDefinitionJpaEntity> existing =
                meterDefinitionJpaRepository.findById(meterDefinition.id());
        if (existing.isPresent()) {
            MeterDefinitionJpaEntity entity = existing.get();
            entity.setDisplayName(meterDefinition.displayName());
            entity.setStatus(meterDefinition.status().name());
            entity.setUpdatedAt(now);
            meterDefinitionJpaRepository.save(entity);
        } else {
            // aggregationType / aggregationWindow / meterKey are write-once (immutable semantics).
            meterDefinitionJpaRepository.save(new MeterDefinitionJpaEntity(
                    meterDefinition.id(),
                    meterDefinition.productId(),
                    meterDefinition.meterKey().value(),
                    meterDefinition.displayName(),
                    meterDefinition.aggregationType().name(),
                    meterDefinition.aggregationWindow().name(),
                    meterDefinition.status().name(),
                    now,
                    now
            ));
        }
        return meterDefinition;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MeterDefinition> findById(UUID id) {
        return meterDefinitionJpaRepository.findById(id).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByProductIdAndMeterKey(UUID productId, String meterKey) {
        return meterDefinitionJpaRepository.existsByProductIdAndMeterKey(productId, meterKey);
    }
}
