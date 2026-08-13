package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.MeterDefinition;
import java.util.Optional;
import java.util.UUID;

public interface MeterDefinitionRepository {

    MeterDefinition save(MeterDefinition meterDefinition);

    Optional<MeterDefinition> findById(UUID id);

    boolean existsByProductIdAndMeterKey(UUID productId, String meterKey);
}
