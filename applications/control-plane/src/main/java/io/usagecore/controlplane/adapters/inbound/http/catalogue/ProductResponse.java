package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.ProductStatus;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String productKey,
        String name,
        ProductStatus status
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.id(),
                product.productKey().value(),
                product.name(),
                product.status()
        );
    }
}
