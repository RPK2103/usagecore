package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contract_version")
class ContractVersionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "source_plan_id")
    private UUID sourcePlanId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContractVersionJpaEntity() {
    }

    ContractVersionJpaEntity(
            UUID id,
            UUID contractId,
            UUID tenantId,
            int versionNumber,
            UUID sourcePlanId,
            String status,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant activatedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.contractId = contractId;
        this.tenantId = tenantId;
        this.versionNumber = versionNumber;
        this.sourcePlanId = sourcePlanId;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.activatedAt = activatedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getContractId() {
        return contractId;
    }

    UUID getTenantId() {
        return tenantId;
    }

    int getVersionNumber() {
        return versionNumber;
    }

    UUID getSourcePlanId() {
        return sourcePlanId;
    }

    String getStatus() {
        return status;
    }

    Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    Instant getEffectiveUntil() {
        return effectiveUntil;
    }

    Instant getActivatedAt() {
        return activatedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setSourcePlanId(UUID sourcePlanId) {
        this.sourcePlanId = sourcePlanId;
    }

    void setStatus(String status) {
        this.status = status;
    }

    void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    void setEffectiveUntil(Instant effectiveUntil) {
        this.effectiveUntil = effectiveUntil;
    }

    void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
