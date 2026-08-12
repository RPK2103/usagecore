package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Product;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductApplicationService {

    private final ProductRepository productRepository;

    public ProductApplicationService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product createProduct(BusinessKey productKey, String name) {
        Objects.requireNonNull(productKey, "productKey");
        if (productRepository.existsByProductKey(productKey.value())) {
            throw new DuplicateResourceException("productKey already exists: " + productKey.value());
        }
        return productRepository.save(Product.create(productKey, name));
    }

    @Transactional(readOnly = true)
    public Product requireProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
    }
}
