package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.FeatureRepository;
import io.usagecore.controlplane.domain.catalogue.Feature;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class FeaturePersistenceAdapter implements FeatureRepository {

    private final FeatureJpaRepository featureJpaRepository;

    FeaturePersistenceAdapter(FeatureJpaRepository featureJpaRepository) {
        this.featureJpaRepository = featureJpaRepository;
    }

    @Override
    @Transactional
    public Feature save(Feature feature) {
        Instant now = Instant.now();
        Optional<FeatureJpaEntity> existing = featureJpaRepository.findById(feature.id());
        if (existing.isPresent()) {
            FeatureJpaEntity entity = existing.get();
            entity.setName(feature.name());
            entity.setStatus(feature.status().name());
            entity.setUpdatedAt(now);
            featureJpaRepository.save(entity);
        } else {
            featureJpaRepository.save(new FeatureJpaEntity(
                    feature.id(),
                    feature.productId(),
                    feature.featureKey().value(),
                    feature.name(),
                    feature.status().name(),
                    now,
                    now
            ));
        }
        return feature;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Feature> findById(UUID id) {
        return featureJpaRepository.findById(id).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Feature> findByProductIdAndFeatureKey(UUID productId, String featureKey) {
        return featureJpaRepository.findByProductIdAndFeatureKey(productId, featureKey)
                .map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Feature> findByProductId(UUID productId) {
        return featureJpaRepository.findByProductId(productId).stream()
                .map(CataloguePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByProductIdAndFeatureKey(UUID productId, String featureKey) {
        return featureJpaRepository.existsByProductIdAndFeatureKey(productId, featureKey);
    }
}
