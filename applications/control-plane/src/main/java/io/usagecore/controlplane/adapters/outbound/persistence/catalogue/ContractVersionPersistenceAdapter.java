package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.application.catalogue.ContractVersionRepository;
import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import io.usagecore.controlplane.domain.catalogue.DomainInvariantException;
import io.usagecore.controlplane.domain.catalogue.Entitlement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ContractVersionPersistenceAdapter implements ContractVersionRepository {

    private final ContractVersionJpaRepository contractVersionJpaRepository;
    private final EntitlementJpaRepository entitlementJpaRepository;
    private final ContractJpaRepository contractJpaRepository;
    private final FeatureJpaRepository featureJpaRepository;

    ContractVersionPersistenceAdapter(
            ContractVersionJpaRepository contractVersionJpaRepository,
            EntitlementJpaRepository entitlementJpaRepository,
            ContractJpaRepository contractJpaRepository,
            FeatureJpaRepository featureJpaRepository
    ) {
        this.contractVersionJpaRepository = contractVersionJpaRepository;
        this.entitlementJpaRepository = entitlementJpaRepository;
        this.contractJpaRepository = contractJpaRepository;
        this.featureJpaRepository = featureJpaRepository;
    }

    @Override
    @Transactional
    public ContractVersion save(ContractVersion contractVersion) {
        Instant now = Instant.now();
        Optional<ContractVersionJpaEntity> existing = contractVersionJpaRepository.findById(contractVersion.id());
        if (existing.isPresent()) {
            ContractVersionJpaEntity entity = existing.get();
            entity.setSourcePlanId(contractVersion.sourcePlanId().orElse(null));
            entity.setStatus(contractVersion.status().name());
            entity.setEffectiveFrom(contractVersion.effectiveFrom());
            entity.setEffectiveUntil(contractVersion.effectiveUntil());
            entity.setActivatedAt(contractVersion.activatedAt().orElse(null));
            entity.setUpdatedAt(now);
            contractVersionJpaRepository.save(entity);
        } else {
            contractVersionJpaRepository.save(new ContractVersionJpaEntity(
                    contractVersion.id(),
                    contractVersion.contractId(),
                    contractVersion.tenantId(),
                    contractVersion.versionNumber(),
                    contractVersion.sourcePlanId().orElse(null),
                    contractVersion.status().name(),
                    contractVersion.effectiveFrom(),
                    contractVersion.effectiveUntil(),
                    contractVersion.activatedAt().orElse(null),
                    now,
                    now
            ));
        }
        syncEntitlements(contractVersion, now);
        return contractVersion;
    }

    private void syncEntitlements(ContractVersion contractVersion, Instant now) {
        List<EntitlementJpaEntity> existingEntitlements =
                entitlementJpaRepository.findByContractVersionId(contractVersion.id());
        Map<UUID, EntitlementJpaEntity> existingById = existingEntitlements.stream()
                .collect(Collectors.toMap(EntitlementJpaEntity::getId, Function.identity()));

        Set<UUID> retainedIds = new HashSet<>();
        for (Entitlement entitlement : contractVersion.entitlements()) {
            retainedIds.add(entitlement.id());
            Long limitQuantity = entitlement.limitConfiguration()
                    .map(limit -> limit.maxQuantity())
                    .orElse(null);
            EntitlementJpaEntity entity = existingById.get(entitlement.id());
            if (entity == null) {
                entitlementJpaRepository.save(new EntitlementJpaEntity(
                        entitlement.id(),
                        contractVersion.id(),
                        entitlement.featureId(),
                        entitlement.entitlementMode().name(),
                        limitQuantity,
                        now,
                        now
                ));
            } else {
                entity.setEntitlementMode(entitlement.entitlementMode().name());
                entity.setLimitQuantity(limitQuantity);
                entity.setUpdatedAt(now);
                entitlementJpaRepository.save(entity);
            }
        }

        for (EntitlementJpaEntity entity : existingEntitlements) {
            if (!retainedIds.contains(entity.getId())) {
                entitlementJpaRepository.delete(entity);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractVersion> findById(UUID id) {
        return contractVersionJpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractVersion> findByContractIdAndVersionNumber(UUID contractId, int versionNumber) {
        return contractVersionJpaRepository.findByContractIdAndVersionNumber(contractId, versionNumber)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractVersion> findByContractId(UUID contractId) {
        return contractVersionJpaRepository.findByContractId(contractId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractVersion> findActivatedByContractId(UUID contractId) {
        return contractVersionJpaRepository.findByContractIdAndStatus(contractId, "ACTIVATED").stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContractVersion> findEffectiveAt(UUID contractId, Instant instant) {
        List<ContractVersionJpaEntity> matches =
                contractVersionJpaRepository.findEffectiveAt(contractId, instant);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new DomainInvariantException(
                    "Multiple effective contract versions found for contract "
                            + contractId
                            + " at "
                            + instant
                            + "; expected zero or exactly one"
            );
        }
        return Optional.of(toDomain(matches.getFirst()));
    }

    @Override
    @Transactional(readOnly = true)
    public int findMaxVersionNumber(UUID contractId) {
        return contractVersionJpaRepository.findMaxVersionNumber(contractId);
    }

    private ContractVersion toDomain(ContractVersionJpaEntity versionEntity) {
        List<EntitlementJpaEntity> entitlementEntities =
                entitlementJpaRepository.findByContractVersionId(versionEntity.getId());
        UUID contractProductId = contractJpaRepository.findById(versionEntity.getContractId())
                .map(ContractJpaEntity::getProductId)
                .orElseThrow(() -> new IllegalStateException("Contract missing for version " + versionEntity.getId()));

        Map<UUID, UUID> featureIdToProductId = new HashMap<>();
        for (EntitlementJpaEntity entitlementEntity : entitlementEntities) {
            featureJpaRepository.findById(entitlementEntity.getFeatureId()).ifPresent(feature ->
                    featureIdToProductId.put(feature.getId(), feature.getProductId())
            );
        }

        return CataloguePersistenceMapper.toDomain(
                versionEntity,
                entitlementEntities,
                contractProductId,
                featureIdToProductId
        );
    }
}
