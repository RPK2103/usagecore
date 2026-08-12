package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.Product;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureApplicationService {

    private final ProductRepository productRepository;
    private final FeatureRepository featureRepository;

    public FeatureApplicationService(
            ProductRepository productRepository,
            FeatureRepository featureRepository
    ) {
        this.productRepository = productRepository;
        this.featureRepository = featureRepository;
    }

    @Transactional
    public Feature createFeature(UUID productId, BusinessKey featureKey, String name) {
        Objects.requireNonNull(featureKey, "featureKey");
        Product product = requireProduct(productId);
        if (featureRepository.existsByProductIdAndFeatureKey(product.id(), featureKey.value())) {
            throw new DuplicateResourceException(
                    "featureKey already exists for product: " + featureKey.value()
            );
        }
        return featureRepository.save(Feature.create(product, featureKey, name));
    }

    @Transactional(readOnly = true)
    public Feature requireFeatureForProduct(UUID productId, UUID featureId) {
        requireProduct(productId);
        Feature feature = featureRepository.findById(featureId)
                .orElseThrow(() -> new ResourceNotFoundException("Feature not found: " + featureId));
        if (!feature.productId().equals(productId)) {
            throw new ResourceNotFoundException(
                    "Feature " + featureId + " not found for product " + productId
            );
        }
        return feature;
    }

    private Product requireProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
    }
}
