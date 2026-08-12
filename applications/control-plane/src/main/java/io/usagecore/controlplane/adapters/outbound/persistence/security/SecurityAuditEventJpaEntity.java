package io.usagecore.controlplane.adapters.outbound.persistence.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_audit_event")
class SecurityAuditEventJpaEntity {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "principal_id", nullable = false, length = 255)
    private String principalId;

    @Column(name = "authenticated_tenant_id")
    private UUID authenticatedTenantId;

    @Column(name = "action", nullable = false, length = 256)
    private String action;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "detail", length = 1024)
    private String detail;

    protected SecurityAuditEventJpaEntity() {
    }

    SecurityAuditEventJpaEntity(
            UUID id,
            Instant occurredAt,
            String eventType,
            String principalId,
            UUID authenticatedTenantId,
            String action,
            String resourceType,
            String resourceId,
            String correlationId,
            String detail
    ) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.eventType = eventType;
        this.principalId = principalId;
        this.authenticatedTenantId = authenticatedTenantId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.correlationId = correlationId;
        this.detail = detail;
    }
}
