package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MeterDefinitionJpaRepository extends JpaRepository<MeterDefinitionJpaEntity, UUID> {

    Optional<MeterDefinitionJpaEntity> findByProductIdAndMeterKey(UUID productId, String meterKey);

    boolean existsByProductIdAndMeterKey(UUID productId, String meterKey);
}
