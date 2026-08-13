package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meter_definition")
class MeterDefinitionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "meter_key", nullable = false, length = 64)
    private String meterKey;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "aggregation_type", nullable = false, length = 32)
    private String aggregationType;

    @Column(name = "aggregation_window", nullable = false, length = 32)
    private String aggregationWindow;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeterDefinitionJpaEntity() {
    }

    MeterDefinitionJpaEntity(
            UUID id,
            UUID productId,
            String meterKey,
            String displayName,
            String aggregationType,
            String aggregationWindow,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.productId = productId;
        this.meterKey = meterKey;
        this.displayName = displayName;
        this.aggregationType = aggregationType;
        this.aggregationWindow = aggregationWindow;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getProductId() {
        return productId;
    }

    String getMeterKey() {
        return meterKey;
    }

    String getDisplayName() {
        return displayName;
    }

    String getAggregationType() {
        return aggregationType;
    }

    String getAggregationWindow() {
        return aggregationWindow;
    }

    String getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    void setStatus(String status) {
        this.status = status;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
