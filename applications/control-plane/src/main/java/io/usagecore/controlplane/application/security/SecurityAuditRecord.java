package io.usagecore.controlplane.application.security;

import java.time.Instant;
import java.util.UUID;

public record SecurityAuditRecord(
        Instant occurredAt,
        SecurityAuditEventType eventType,
        String principalId,
        UUID authenticatedTenantId,
        String action,
        String resourceType,
        String resourceId,
        String correlationId,
        String detail
) {
}
