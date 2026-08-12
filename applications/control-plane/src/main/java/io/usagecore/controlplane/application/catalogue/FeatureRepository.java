package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.Feature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureRepository {

    Feature save(Feature feature);

    Optional<Feature> findById(UUID id);

    Optional<Feature> findByProductIdAndFeatureKey(UUID productId, String featureKey);

    List<Feature> findByProductId(UUID productId);

    boolean existsByProductIdAndFeatureKey(UUID productId, String featureKey);
}
