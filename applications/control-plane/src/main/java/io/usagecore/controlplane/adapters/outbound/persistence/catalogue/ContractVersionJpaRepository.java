package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ContractVersionJpaRepository extends JpaRepository<ContractVersionJpaEntity, UUID> {

    List<ContractVersionJpaEntity> findByContractId(UUID contractId);

    Optional<ContractVersionJpaEntity> findByContractIdAndVersionNumber(UUID contractId, int versionNumber);

    List<ContractVersionJpaEntity> findByContractIdAndStatus(UUID contractId, String status);

    @Query("""
            SELECT cv FROM ContractVersionJpaEntity cv
            WHERE cv.contractId = :contractId
              AND cv.status = 'ACTIVATED'
              AND cv.effectiveFrom <= :instant
              AND (cv.effectiveUntil IS NULL OR cv.effectiveUntil > :instant)
            """)
    List<ContractVersionJpaEntity> findEffectiveAt(
            @Param("contractId") UUID contractId,
            @Param("instant") Instant instant
    );

    @Query("""
            SELECT COALESCE(MAX(cv.versionNumber), 0) FROM ContractVersionJpaEntity cv
            WHERE cv.contractId = :contractId
            """)
    int findMaxVersionNumber(@Param("contractId") UUID contractId);
}
