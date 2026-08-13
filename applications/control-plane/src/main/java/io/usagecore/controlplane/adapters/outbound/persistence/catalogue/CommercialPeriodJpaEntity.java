package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "commercial_period")
class CommercialPeriodJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closing_started_at")
    private Instant closingStartedAt;

    @Column(name = "reconciling_started_at")
    private Instant reconcilingStartedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by", length = 255)
    private String finalizedBy;

    protected CommercialPeriodJpaEntity() {
    }

    CommercialPeriodJpaEntity(
            UUID id,
            UUID tenantId,
            UUID productId,
            Instant periodStart,
            Instant periodEnd,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Instant closingStartedAt,
            Instant reconcilingStartedAt,
            Instant finalizedAt,
            String finalizedBy
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.productId = productId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closingStartedAt = closingStartedAt;
        this.reconcilingStartedAt = reconcilingStartedAt;
        this.finalizedAt = finalizedAt;
        this.finalizedBy = finalizedBy;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getProductId() {
        return productId;
    }

    Instant getPeriodStart() {
        return periodStart;
    }

    Instant getPeriodEnd() {
        return periodEnd;
    }

    String getStatus() {
        return status;
    }

    Instant getClosingStartedAt() {
        return closingStartedAt;
    }

    Instant getReconcilingStartedAt() {
        return reconcilingStartedAt;
    }

    Instant getFinalizedAt() {
        return finalizedAt;
    }

    String getFinalizedBy() {
        return finalizedBy;
    }
}
