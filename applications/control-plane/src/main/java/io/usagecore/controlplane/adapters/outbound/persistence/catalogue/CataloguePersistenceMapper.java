package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.ContractStatus;
import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import io.usagecore.controlplane.domain.catalogue.ContractVersionStatus;
import io.usagecore.controlplane.domain.catalogue.Entitlement;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.FeatureStatus;
import io.usagecore.controlplane.domain.catalogue.LimitConfiguration;
import io.usagecore.controlplane.domain.catalogue.Plan;
import io.usagecore.controlplane.domain.catalogue.PlanFeature;
import io.usagecore.controlplane.domain.catalogue.PlanStatus;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.ProductStatus;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import io.usagecore.controlplane.domain.catalogue.TenantStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class CataloguePersistenceMapper {

    private CataloguePersistenceMapper() {
    }

    static Tenant toDomain(TenantJpaEntity entity) {
        return Tenant.reconstitute(
                entity.getId(),
                BusinessKey.of(entity.getTenantKey()),
                entity.getDisplayName(),
                TenantStatus.valueOf(entity.getStatus())
        );
    }

    static Product toDomain(ProductJpaEntity entity) {
        return Product.reconstitute(
                entity.getId(),
                BusinessKey.of(entity.getProductKey()),
                entity.getName(),
                ProductStatus.valueOf(entity.getStatus())
        );
    }

    static Feature toDomain(FeatureJpaEntity entity) {
        return Feature.reconstitute(
                entity.getId(),
                entity.getProductId(),
                BusinessKey.of(entity.getFeatureKey()),
                entity.getName(),
                FeatureStatus.valueOf(entity.getStatus())
        );
    }

    static Plan toDomain(
            PlanJpaEntity planEntity,
            List<PlanFeatureJpaEntity> planFeatureEntities,
            Map<UUID, UUID> featureIdToProductId
    ) {
        List<PlanFeature> planFeatures = planFeatureEntities.stream()
                .map(entity -> toDomain(entity, featureIdToProductId.get(entity.getFeatureId())))
                .collect(Collectors.toList());
        return Plan.reconstitute(
                planEntity.getId(),
                planEntity.getProductId(),
                BusinessKey.of(planEntity.getPlanKey()),
                planEntity.getName(),
                PlanStatus.valueOf(planEntity.getStatus()),
                planFeatures
        );
    }

    static PlanFeature toDomain(PlanFeatureJpaEntity entity, UUID featureProductId) {
        LimitConfiguration limit = entity.getLimitQuantity() == null
                ? null
                : LimitConfiguration.ofMaxQuantity(entity.getLimitQuantity());
        return PlanFeature.reconstitute(
                entity.getId(),
                entity.getPlanId(),
                entity.getFeatureId(),
                featureProductId,
                EntitlementMode.valueOf(entity.getEntitlementMode()),
                limit
        );
    }

    static Contract toDomain(ContractJpaEntity entity) {
        return Contract.reconstitute(
                entity.getId(),
                entity.getTenantId(),
                entity.getProductId(),
                BusinessKey.of(entity.getContractKey()),
                ContractStatus.valueOf(entity.getStatus())
        );
    }

    static ContractVersion toDomain(
            ContractVersionJpaEntity versionEntity,
            List<EntitlementJpaEntity> entitlementEntities,
            UUID contractProductId,
            Map<UUID, UUID> featureIdToProductId
    ) {
        List<Entitlement> entitlements = entitlementEntities.stream()
                .map(entity -> toDomain(entity, featureIdToProductId.get(entity.getFeatureId())))
                .collect(Collectors.toList());
        return ContractVersion.reconstitute(
                versionEntity.getId(),
                versionEntity.getContractId(),
                versionEntity.getTenantId(),
                contractProductId,
                versionEntity.getVersionNumber(),
                versionEntity.getSourcePlanId(),
                ContractVersionStatus.valueOf(versionEntity.getStatus()),
                versionEntity.getEffectiveFrom(),
                versionEntity.getEffectiveUntil(),
                versionEntity.getActivatedAt(),
                entitlements
        );
    }

    static Entitlement toDomain(EntitlementJpaEntity entity, UUID featureProductId) {
        LimitConfiguration limit = entity.getLimitQuantity() == null
                ? null
                : LimitConfiguration.ofMaxQuantity(entity.getLimitQuantity());
        return Entitlement.reconstitute(
                entity.getId(),
                entity.getContractVersionId(),
                entity.getFeatureId(),
                featureProductId,
                EntitlementMode.valueOf(entity.getEntitlementMode()),
                limit
        );
    }
}
