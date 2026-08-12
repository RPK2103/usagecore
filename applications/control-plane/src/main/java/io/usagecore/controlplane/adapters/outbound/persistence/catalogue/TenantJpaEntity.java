package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant")
class TenantJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_key", nullable = false, length = 64, unique = true)
    private String tenantKey;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantJpaEntity() {
    }

    TenantJpaEntity(
            UUID id,
            String tenantKey,
            String displayName,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.tenantKey = tenantKey;
        this.displayName = displayName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    String getTenantKey() {
        return tenantKey;
    }

    String getDisplayName() {
        return displayName;
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
