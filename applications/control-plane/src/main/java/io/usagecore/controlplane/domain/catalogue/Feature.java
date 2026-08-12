package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.UUID;

/**
 * Gateable or meterable capability belonging to exactly one {@link Product}.
 * {@code featureKey} is unique within that product.
 */
public final class Feature {

    private final UUID id;
    private final UUID productId;
    private final BusinessKey featureKey;
    private String name;
    private FeatureStatus status;

    private Feature(UUID id, UUID productId, BusinessKey featureKey, String name, FeatureStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.featureKey = Objects.requireNonNull(featureKey, "featureKey");
        this.name = DisplayNames.requireNonBlank(name, "name");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static Feature create(Product product, BusinessKey featureKey, String name) {
        Objects.requireNonNull(product, "product");
        return new Feature(UUID.randomUUID(), product.id(), featureKey, name, FeatureStatus.ACTIVE);
    }

    public static Feature reconstitute(
            UUID id,
            UUID productId,
            BusinessKey featureKey,
            String name,
            FeatureStatus status
    ) {
        return new Feature(id, productId, featureKey, name, status);
    }

    public void rename(String name) {
        assertNotArchived();
        this.name = DisplayNames.requireNonBlank(name, "name");
    }

    public void archive() {
        this.status = FeatureStatus.ARCHIVED;
    }

    public void assertBelongsToProduct(UUID expectedProductId) {
        Objects.requireNonNull(expectedProductId, "expectedProductId");
        if (!productId.equals(expectedProductId)) {
            throw new DomainInvariantException("Feature does not belong to the expected product");
        }
    }

    private void assertNotArchived() {
        if (status == FeatureStatus.ARCHIVED) {
            throw new DomainInvariantException("ARCHIVED feature cannot be modified");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID productId() {
        return productId;
    }

    public BusinessKey featureKey() {
        return featureKey;
    }

    public String name() {
        return name;
    }

    public FeatureStatus status() {
        return status;
    }
}
