package io.usagecore.controlplane.application.catalogue;

import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.LimitConfiguration;
import io.usagecore.controlplane.domain.catalogue.Plan;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractVersionApplicationService {

    private final ContractRepository contractRepository;
    private final ContractVersionRepository contractVersionRepository;
    private final PlanRepository planRepository;
    private final FeatureRepository featureRepository;

    public ContractVersionApplicationService(
            ContractRepository contractRepository,
            ContractVersionRepository contractVersionRepository,
            PlanRepository planRepository,
            FeatureRepository featureRepository
    ) {
        this.contractRepository = contractRepository;
        this.contractVersionRepository = contractVersionRepository;
        this.planRepository = planRepository;
        this.featureRepository = featureRepository;
    }

    @Transactional
    public ContractVersion createDraftVersion(
            UUID contractId,
            Instant effectiveFrom,
            Instant effectiveUntil
    ) {
        Contract contract = requireContract(contractId);
        ContractVersion version = ContractVersion.createDraft(
                contract,
                allocateNextVersionNumber(contract.id()),
                effectiveFrom,
                effectiveUntil
        );
        return contractVersionRepository.save(version);
    }

    @Transactional
    public ContractVersion createDraftFromPlan(
            UUID contractId,
            UUID planId,
            Instant effectiveFrom,
            Instant effectiveUntil
    ) {
        Contract contract = requireContract(contractId);
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + planId));
        ContractVersion version = ContractVersion.createDraftFromPlan(
                contract,
                plan,
                allocateNextVersionNumber(contract.id()),
                effectiveFrom,
                effectiveUntil
        );
        return contractVersionRepository.save(version);
    }

    @Transactional
    public ContractVersion upsertDraftEntitlement(
            UUID contractId,
            int versionNumber,
            UUID featureId,
            EntitlementMode mode,
            LimitConfiguration limit
    ) {
        ContractVersion version = requireVersion(contractId, versionNumber);
        Feature feature = featureRepository.findById(featureId)
                .orElseThrow(() -> new ResourceNotFoundException("Feature not found: " + featureId));
        boolean present = version.entitlements().stream()
                .anyMatch(entitlement -> entitlement.featureId().equals(featureId));
        if (present) {
            version.updateEntitlement(featureId, mode, limit);
        } else {
            version.addEntitlement(feature, mode, limit);
        }
        return contractVersionRepository.save(version);
    }

    @Transactional
    public ContractVersion activateVersion(UUID contractId, int versionNumber) {
        ContractVersion version = requireVersion(contractId, versionNumber);
        List<ContractVersion> activated = contractVersionRepository.findActivatedByContractId(version.contractId());
        version.activate(Instant.now(), activated);
        return contractVersionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public ContractVersion requireVersion(UUID contractId, int versionNumber) {
        requireContract(contractId);
        return contractVersionRepository.findByContractIdAndVersionNumber(contractId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Contract version not found: contractId="
                                + contractId
                                + ", versionNumber="
                                + versionNumber
                ));
    }

    @Transactional(readOnly = true)
    public Optional<ContractVersion> resolveEffectiveVersion(UUID contractId, Instant instant) {
        Objects.requireNonNull(instant, "instant");
        requireContract(contractId);
        return contractVersionRepository.findEffectiveAt(contractId, instant);
    }

    /**
     * Version numbers are owned by the application layer. Callers must not supply
     * arbitrary values; allocation is max(existing) + 1 within the current transaction.
     */
    private int allocateNextVersionNumber(UUID contractId) {
        return contractVersionRepository.findMaxVersionNumber(contractId) + 1;
    }

    private Contract requireContract(UUID contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract not found: " + contractId));
    }
}
