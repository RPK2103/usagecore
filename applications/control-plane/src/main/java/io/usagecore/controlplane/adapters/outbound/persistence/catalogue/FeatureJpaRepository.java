package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FeatureJpaRepository extends JpaRepository<FeatureJpaEntity, UUID> {

    Optional<FeatureJpaEntity> findByProductIdAndFeatureKey(UUID productId, String featureKey);

    List<FeatureJpaEntity> findByProductId(UUID productId);

    boolean existsByProductIdAndFeatureKey(UUID productId, String featureKey);
}
