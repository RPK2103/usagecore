package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface EntitlementJpaRepository extends JpaRepository<EntitlementJpaEntity, UUID> {

    List<EntitlementJpaEntity> findByContractVersionId(UUID contractVersionId);

    void deleteByContractVersionId(UUID contractVersionId);
}
