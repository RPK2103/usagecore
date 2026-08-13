package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.UUID;

/**
 * Product-scoped meter configuration that defines how usage is aggregated.
 * {@code meterKey} is unique within a product. Owned by Control Plane catalogue.
 * <p>
 * Each meter is explicitly governed by one Feature ({@code featureId}) for
 * contract-aware quota enforcement when created via Phase 6C APIs.
 * Feature identity is never inferred from meter key string similarity.
 * <p>
 * Legacy pre-V10 meters may temporarily have {@code featureId == null} after upgrade;
 * they cannot participate in strict quota until remediated.
 * <p>
 * Semantic fields {@code meterKey}, {@code featureId} (once set), {@code aggregationType}, and
 * {@code aggregationWindow} are immutable after creation so historical ledger
 * rebuild and quota mapping remain deterministic.
 */
public final class MeterDefinition {

    private final UUID id;
    private final UUID productId;
    private final UUID featureId;
    private final BusinessKey meterKey;
    private String displayName;
    private final AggregationType aggregationType;
    private final AggregationWindow aggregationWindow;
    private MeterStatus status;

    private MeterDefinition(
            UUID id,
            UUID productId,
            UUID featureId,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            AggregationWindow aggregationWindow,
            MeterStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.featureId = featureId; // nullable for legacy unbound rows only
        this.meterKey = Objects.requireNonNull(meterKey, "meterKey");
        this.displayName = DisplayNames.requireNonBlank(displayName, "displayName");
        this.aggregationType = Objects.requireNonNull(aggregationType, "aggregationType");
        this.aggregationWindow = Objects.requireNonNull(aggregationWindow, "aggregationWindow");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static MeterDefinition create(
            Product product,
            Feature feature,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            AggregationWindow aggregationWindow
    ) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(feature, "feature");
        if (!feature.productId().equals(product.id())) {
            throw new IllegalArgumentException("Feature must belong to the same product as the meter");
        }
        return new MeterDefinition(
                UUID.randomUUID(),
                product.id(),
                feature.id(),
                meterKey,
                displayName,
                aggregationType,
                aggregationWindow,
                MeterStatus.ACTIVE
        );
    }

    /**
     * Rehydrates catalogue state, including legacy unbound meters ({@code featureId} null).
     */
    public static MeterDefinition reconstitute(
            UUID id,
            UUID productId,
            UUID featureId,
            BusinessKey meterKey,
            String displayName,
            AggregationType aggregationType,
            AggregationWindow aggregationWindow,
            MeterStatus status
    ) {
        return new MeterDefinition(
                id,
                productId,
                featureId,
                meterKey,
                displayName,
                aggregationType,
                aggregationWindow,
                status
        );
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

    public UUID featureId() {
        return featureId;
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

    public AggregationWindow aggregationWindow() {
        return aggregationWindow;
    }

    public MeterStatus status() {
        return status;
    }
}
