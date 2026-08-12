package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.UUID;

/**
 * Globally identified sellable product surface.
 */
public final class Product {

    private final UUID id;
    private final BusinessKey productKey;
    private String name;
    private ProductStatus status;

    private Product(UUID id, BusinessKey productKey, String name, ProductStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.productKey = Objects.requireNonNull(productKey, "productKey");
        this.name = DisplayNames.requireNonBlank(name, "name");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static Product create(BusinessKey productKey, String name) {
        return new Product(UUID.randomUUID(), productKey, name, ProductStatus.ACTIVE);
    }

    public static Product reconstitute(UUID id, BusinessKey productKey, String name, ProductStatus status) {
        return new Product(id, productKey, name, status);
    }

    public void rename(String name) {
        assertNotArchived();
        this.name = DisplayNames.requireNonBlank(name, "name");
    }

    public void archive() {
        this.status = ProductStatus.ARCHIVED;
    }

    private void assertNotArchived() {
        if (status == ProductStatus.ARCHIVED) {
            throw new DomainInvariantException("ARCHIVED product cannot be modified");
        }
    }

    public UUID id() {
        return id;
    }

    public BusinessKey productKey() {
        return productKey;
    }

    public String name() {
        return name;
    }

    public ProductStatus status() {
        return status;
    }
}
