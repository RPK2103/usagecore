package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product")
class ProductJpaEntity {

    @Id
    private UUID id;

    @Column(name = "product_key", nullable = false, length = 64, unique = true)
    private String productKey;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductJpaEntity() {
    }

    ProductJpaEntity(
            UUID id,
            String productKey,
            String name,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.productKey = productKey;
        this.name = name;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    String getProductKey() {
        return productKey;
    }

    String getName() {
        return name;
    }

    String getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setName(String name) {
        this.name = name;
    }

    void setStatus(String status) {
        this.status = status;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
