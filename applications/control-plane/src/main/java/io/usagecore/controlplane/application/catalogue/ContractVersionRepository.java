package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractVersionRepository {

    ContractVersion save(ContractVersion contractVersion);

    Optional<ContractVersion> findById(UUID id);

    List<ContractVersion> findByContractId(UUID contractId);

    List<ContractVersion> findActivatedByContractId(UUID contractId);

    Optional<ContractVersion> findEffectiveAt(UUID contractId, Instant instant);

    int findMaxVersionNumber(UUID contractId);
}
