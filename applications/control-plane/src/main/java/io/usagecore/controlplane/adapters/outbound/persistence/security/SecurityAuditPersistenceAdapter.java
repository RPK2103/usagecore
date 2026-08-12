package io.usagecore.controlplane.adapters.outbound.persistence.security;

import io.usagecore.controlplane.application.security.SecurityAuditRecord;
import io.usagecore.controlplane.application.security.SecurityAuditRecorder;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class SecurityAuditPersistenceAdapter implements SecurityAuditRecorder {

    private final SecurityAuditEventJpaRepository repository;

    SecurityAuditPersistenceAdapter(SecurityAuditEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(SecurityAuditRecord record) {
        repository.save(new SecurityAuditEventJpaEntity(
                UUID.randomUUID(),
                record.occurredAt(),
                record.eventType().name(),
                record.principalId(),
                record.authenticatedTenantId(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.correlationId(),
                record.detail()
        ));
    }
}
