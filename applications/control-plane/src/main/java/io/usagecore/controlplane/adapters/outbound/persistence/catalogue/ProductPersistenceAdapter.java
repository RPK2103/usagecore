package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.ProductRepository;
import io.usagecore.controlplane.domain.catalogue.Product;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProductPersistenceAdapter implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    ProductPersistenceAdapter(ProductJpaRepository productJpaRepository) {
        this.productJpaRepository = productJpaRepository;
    }

    @Override
    @Transactional
    public Product save(Product product) {
        Instant now = Instant.now();
        Optional<ProductJpaEntity> existing = productJpaRepository.findById(product.id());
        if (existing.isPresent()) {
            ProductJpaEntity entity = existing.get();
            entity.setName(product.name());
            entity.setStatus(product.status().name());
            entity.setUpdatedAt(now);
            productJpaRepository.save(entity);
        } else {
            productJpaRepository.save(new ProductJpaEntity(
                    product.id(),
                    product.productKey().value(),
                    product.name(),
                    product.status().name(),
                    now,
                    now
            ));
        }
        return product;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(UUID id) {
        return productJpaRepository.findById(id).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findByProductKey(String productKey) {
        return productJpaRepository.findByProductKey(productKey).map(CataloguePersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByProductKey(String productKey) {
        return productJpaRepository.existsByProductKey(productKey);
    }
}
