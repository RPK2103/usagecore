package io.usagecore.controlplane.adapters.outbound.persistence.security;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SecurityAuditEventJpaRepository extends JpaRepository<SecurityAuditEventJpaEntity, UUID> {
}
