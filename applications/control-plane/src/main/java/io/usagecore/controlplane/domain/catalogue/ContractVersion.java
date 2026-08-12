package io.usagecore.controlplane.domain.catalogue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned commercial terms for a {@link Contract}. DRAFT versions are mutable;
 * ACTIVATED versions are immutable historical evidence (ADR-003).
 */
public final class ContractVersion {

    private final UUID id;
    private final UUID contractId;
    private final UUID tenantId;
    private final UUID contractProductId;
    private final int versionNumber;
    private UUID sourcePlanId;
    private ContractVersionStatus status;
    private Instant effectiveFrom;
    private Instant effectiveUntil;
    private Instant activatedAt;
    private final List<Entitlement> entitlements;

    private ContractVersion(
            UUID id,
            UUID contractId,
            UUID tenantId,
            UUID contractProductId,
            int versionNumber,
            UUID sourcePlanId,
            ContractVersionStatus status,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant activatedAt,
            List<Entitlement> entitlements
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.contractId = Objects.requireNonNull(contractId, "contractId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.contractProductId = Objects.requireNonNull(contractProductId, "contractProductId");
        if (versionNumber <= 0) {
            throw new DomainInvariantException("versionNumber must be positive");
        }
        this.versionNumber = versionNumber;
        this.sourcePlanId = sourcePlanId;
        this.status = Objects.requireNonNull(status, "status");
        this.entitlements = new ArrayList<>(Objects.requireNonNull(entitlements, "entitlements"));
        setEffectiveInterval(effectiveFrom, effectiveUntil, false);
        this.activatedAt = activatedAt;
        assertActivatedAtConsistency();
    }

    public static ContractVersion createDraft(
            Contract contract,
            int versionNumber,
            Instant effectiveFrom,
            Instant effectiveUntil
    ) {
        Objects.requireNonNull(contract, "contract");
        return new ContractVersion(
                UUID.randomUUID(),
                contract.id(),
                contract.tenantId(),
                contract.productId(),
                versionNumber,
                null,
                ContractVersionStatus.DRAFT,
                effectiveFrom,
                effectiveUntil,
                null,
                new ArrayList<>()
        );
    }

    public static ContractVersion createDraftFromPlan(
            Contract contract,
            Plan plan,
            int versionNumber,
            Instant effectiveFrom,
            Instant effectiveUntil
    ) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.productId().equals(contract.productId())) {
            throw new DomainInvariantException("Plan does not belong to the contract product");
        }
        ContractVersion version = createDraft(contract, versionNumber, effectiveFrom, effectiveUntil);
        version.sourcePlanId = plan.id();
        for (PlanFeature planFeature : plan.planFeatures()) {
            version.entitlements.add(
                    Entitlement.fromPlanFeature(version.id, contract.productId(), planFeature)
            );
        }
        return version;
    }

    public static ContractVersion reconstitute(
            UUID id,
            UUID contractId,
            UUID tenantId,
            UUID contractProductId,
            int versionNumber,
            UUID sourcePlanId,
            ContractVersionStatus status,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant activatedAt,
            List<Entitlement> entitlements
    ) {
        return new ContractVersion(
                id,
                contractId,
                tenantId,
                contractProductId,
                versionNumber,
                sourcePlanId,
                status,
                effectiveFrom,
                effectiveUntil,
                activatedAt,
                entitlements
        );
    }

    public void setEffectiveInterval(Instant effectiveFrom, Instant effectiveUntil) {
        assertDraftMutation();
        setEffectiveInterval(effectiveFrom, effectiveUntil, true);
    }

    private void setEffectiveInterval(Instant effectiveFrom, Instant effectiveUntil, boolean validateDraft) {
        if (validateDraft) {
            assertDraftMutation();
        }
        EffectiveInterval.of(
                Objects.requireNonNull(effectiveFrom, "effectiveFrom"),
                effectiveUntil
        );
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
    }

    public Entitlement addEntitlement(Feature feature, EntitlementMode mode, LimitConfiguration limit) {
        assertDraftMutation();
        Objects.requireNonNull(feature, "feature");
        feature.assertBelongsToProduct(contractProductId);
        if (findEntitlement(feature.id()).isPresent()) {
            throw new DomainInvariantException("Contract version already contains feature " + feature.featureKey());
        }
        Entitlement entitlement = Entitlement.create(id, feature, mode, limit);
        entitlements.add(entitlement);
        return entitlement;
    }

    public void updateEntitlement(UUID featureId, EntitlementMode mode, LimitConfiguration limit) {
        assertDraftMutation();
        requireEntitlement(featureId).reconfigure(mode, limit);
    }

    public void removeEntitlement(UUID featureId) {
        assertDraftMutation();
        Entitlement entitlement = requireEntitlement(featureId);
        entitlements.remove(entitlement);
    }

    public void activate(Instant activatedAt, Collection<ContractVersion> existingActivatedVersions) {
        assertDraftMutation();
        Objects.requireNonNull(activatedAt, "activatedAt");
        EffectiveInterval thisInterval = effectiveInterval();
        for (ContractVersion other : existingActivatedVersions) {
            Objects.requireNonNull(other, "existingActivatedVersions element");
            if (other.id().equals(id)) {
                continue;
            }
            if (other.status() != ContractVersionStatus.ACTIVATED) {
                continue;
            }
            if (thisInterval.overlaps(other.effectiveInterval())) {
                throw new DomainInvariantException(
                        "Activated contract version intervals must not overlap for the same contract"
                );
            }
        }
        assertEntitlementsValid();
        this.status = ContractVersionStatus.ACTIVATED;
        this.activatedAt = activatedAt;
    }

    public boolean isEffectiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (status != ContractVersionStatus.ACTIVATED) {
            return false;
        }
        return effectiveInterval().contains(instant);
    }

    public EffectiveInterval effectiveInterval() {
        return EffectiveInterval.of(effectiveFrom, effectiveUntil);
    }

    private void assertEntitlementsValid() {
        for (Entitlement entitlement : entitlements) {
            entitlement.featureProductId();
            if (!entitlement.featureProductId().equals(contractProductId)) {
                throw new DomainInvariantException("Entitlement feature must belong to the contract product");
            }
        }
    }

    private void assertDraftMutation() {
        if (status == ContractVersionStatus.ACTIVATED) {
            throw new DomainInvariantException("ACTIVATED contract version cannot be mutated");
        }
    }

    private void assertActivatedAtConsistency() {
        if (status == ContractVersionStatus.DRAFT && activatedAt != null) {
            throw new DomainInvariantException("DRAFT contract version must not have activatedAt");
        }
        if (status == ContractVersionStatus.ACTIVATED && activatedAt == null) {
            throw new DomainInvariantException("ACTIVATED contract version requires activatedAt");
        }
    }

    private Optional<Entitlement> findEntitlement(UUID featureId) {
        return entitlements.stream()
                .filter(entitlement -> entitlement.featureId().equals(featureId))
                .findFirst();
    }

    private Entitlement requireEntitlement(UUID featureId) {
        return findEntitlement(featureId)
                .orElseThrow(() -> new DomainInvariantException("Feature is not part of this contract version"));
    }

    public UUID id() {
        return id;
    }

    public UUID contractId() {
        return contractId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID contractProductId() {
        return contractProductId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public Optional<UUID> sourcePlanId() {
        return Optional.ofNullable(sourcePlanId);
    }

    public ContractVersionStatus status() {
        return status;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveUntil() {
        return effectiveUntil;
    }

    public Optional<Instant> activatedAt() {
        return Optional.ofNullable(activatedAt);
    }

    public List<Entitlement> entitlements() {
        return Collections.unmodifiableList(entitlements);
    }
}
