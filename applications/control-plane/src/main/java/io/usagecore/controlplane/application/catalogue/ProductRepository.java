package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.Product;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(UUID id);

    Optional<Product> findByProductKey(String productKey);

    boolean existsByProductKey(String productKey);
}
