package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.UUID;

/**
 * Product-scoped meter configuration that defines how usage is aggregated.
 * {@code meterKey} is unique within a product. Owned by Control Plane catalogue.
 */
public final class MeterDefinition {

    private final UUID id;
    private final UUID productId;
    private final BusinessKey meterKey;
    private String displayName;
    private final AggregationType aggregationType;
    private MeterStatus status;

    private MeterDefinition(
            UUID id,
            UUID productId,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            MeterStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.meterKey = Objects.requireNonNull(meterKey, "meterKey");
        this.displayName = DisplayNames.requireNonBlank(displayName, "displayName");
        this.aggregationType = Objects.requireNonNull(aggregationType, "aggregationType");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static MeterDefinition create(
            Product product,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType
    ) {
        Objects.requireNonNull(product, "product");
        return new MeterDefinition(
                UUID.randomUUID(),
                product.id(),
                meterKey,
                displayName,
                aggregationType,
                MeterStatus.ACTIVE
        );
    }

    public static MeterDefinition reconstitute(
            UUID id,
            UUID productId,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            MeterStatus status
    ) {
        return new MeterDefinition(id, productId, meterKey, displayName, aggregationType, status);
    }

    public void deactivate() {
        this.status = MeterStatus.INACTIVE;
    }

    public void activate() {
        this.status = MeterStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID productId() {
        return productId;
    }

    public BusinessKey meterKey() {
        return meterKey;
    }

    public String displayName() {
        return displayName;
    }

    public AggregationType aggregationType() {
        return aggregationType;
    }

    public MeterStatus status() {
        return status;
    }
}
