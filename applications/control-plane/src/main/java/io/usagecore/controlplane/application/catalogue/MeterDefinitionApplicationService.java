package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.AggregationType;
import io.usagecore.controlplane.domain.catalogue.AggregationWindow;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.MeterDefinition;
import io.usagecore.controlplane.domain.catalogue.Product;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeterDefinitionApplicationService {

    private final ProductRepository productRepository;
    private final FeatureRepository featureRepository;
    private final MeterDefinitionRepository meterDefinitionRepository;

    public MeterDefinitionApplicationService(
            ProductRepository productRepository,
            FeatureRepository featureRepository,
            MeterDefinitionRepository meterDefinitionRepository
    ) {
        this.productRepository = productRepository;
        this.featureRepository = featureRepository;
        this.meterDefinitionRepository = meterDefinitionRepository;
    }

    @Transactional
    public MeterDefinition createMeter(
            UUID productId,
            UUID featureId,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            AggregationWindow aggregationWindow
    ) {
        Objects.requireNonNull(featureId, "featureId");
        Objects.requireNonNull(meterKey, "meterKey");
        Objects.requireNonNull(aggregationType, "aggregationType");
        Objects.requireNonNull(aggregationWindow, "aggregationWindow");
        Product product = requireProduct(productId);
        Feature feature = requireFeatureForProduct(productId, featureId);
        if (meterDefinitionRepository.existsByProductIdAndMeterKey(product.id(), meterKey.value())) {
            throw new DuplicateResourceException(
                    "meterKey already exists for product: " + meterKey.value()
            );
        }
        return meterDefinitionRepository.save(
                MeterDefinition.create(product, feature, meterKey, displayName, aggregationType, aggregationWindow)
        );
    }

    @Transactional(readOnly = true)
    public MeterDefinition requireMeterForProduct(UUID productId, UUID meterId) {
        requireProduct(productId);
        MeterDefinition meter = meterDefinitionRepository.findById(meterId)
                .orElseThrow(() -> new ResourceNotFoundException("Meter not found: " + meterId));
        if (!meter.productId().equals(productId)) {
            throw new ResourceNotFoundException(
                    "Meter " + meterId + " not found for product " + productId
            );
        }
        return meter;
    }

    private Product requireProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
    }

    private Feature requireFeatureForProduct(UUID productId, UUID featureId) {
        Feature feature = featureRepository.findById(featureId)
                .orElseThrow(() -> new ResourceNotFoundException("Feature not found: " + featureId));
        if (!feature.productId().equals(productId)) {
            throw new ResourceNotFoundException(
                    "Feature " + featureId + " not found for product " + productId
            );
        }
        return feature;
    }
}
