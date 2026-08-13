package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.AggregationType;
import io.usagecore.controlplane.domain.catalogue.AggregationWindow;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.MeterDefinition;
import io.usagecore.controlplane.domain.catalogue.Product;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeterDefinitionApplicationService {

    private final ProductRepository productRepository;
    private final MeterDefinitionRepository meterDefinitionRepository;

    public MeterDefinitionApplicationService(
            ProductRepository productRepository,
            MeterDefinitionRepository meterDefinitionRepository
    ) {
        this.productRepository = productRepository;
        this.meterDefinitionRepository = meterDefinitionRepository;
    }

    @Transactional
    public MeterDefinition createMeter(
            UUID productId,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            AggregationWindow aggregationWindow
    ) {
        Objects.requireNonNull(meterKey, "meterKey");
        Objects.requireNonNull(aggregationType, "aggregationType");
        Objects.requireNonNull(aggregationWindow, "aggregationWindow");
        Product product = requireProduct(productId);
        if (meterDefinitionRepository.existsByProductIdAndMeterKey(product.id(), meterKey.value())) {
            throw new DuplicateResourceException(
                    "meterKey already exists for product: " + meterKey.value()
            );
        }
        return meterDefinitionRepository.save(
                MeterDefinition.create(product, meterKey, displayName, aggregationType, aggregationWindow)
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
}
