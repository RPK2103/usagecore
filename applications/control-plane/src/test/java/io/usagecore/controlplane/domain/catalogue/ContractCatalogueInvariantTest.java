package io.usagecore.controlplane.domain.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractCatalogueInvariantTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN_1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant DEC_1 = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void contractRequiresValidTenantProductAndBusinessKey() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");

        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        assertThat(contract.tenantId()).isEqualTo(tenant.id());
        assertThat(contract.productId()).isEqualTo(product.id());
        assertThat(contract.contractKey().value()).isEqualTo("acme-datapilot");
        assertThat(contract.status()).isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void versionNumberMustBePositive() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        assertThatThrownBy(() -> ContractVersion.createDraft(contract, 0, JAN_1, JUN_1))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void invalidEffectiveIntervalRejected() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        assertThatThrownBy(() -> ContractVersion.createDraft(contract, 1, JUN_1, JAN_1))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("effectiveUntil");

        assertThatThrownBy(() -> ContractVersion.createDraft(contract, 1, JAN_1, JAN_1))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("effectiveUntil");
    }

    @Test
    void futureDatedActivatedVersionAllowed() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, DEC_1, null);

        version.activate(Instant.parse("2026-01-15T00:00:00Z"), List.of());

        assertThat(version.status()).isEqualTo(ContractVersionStatus.ACTIVATED);
        assertThat(version.isEffectiveAt(Instant.parse("2026-11-30T23:59:59Z"))).isFalse();
        assertThat(version.isEffectiveAt(DEC_1)).isTrue();
    }

    @Test
    void draftEntitlementsCanChange() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);

        version.addEntitlement(feature, EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(50));
        version.updateEntitlement(feature.id(), EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(200));
        version.removeEntitlement(feature.id());

        assertThat(version.entitlements()).isEmpty();
    }

    @Test
    void activatedVersionAndEntitlementsCannotBeMutated() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);
        version.addEntitlement(feature, EntitlementMode.ENABLED, null);
        version.activate(Instant.parse("2026-01-01T00:00:00Z"), List.of());

        assertThatThrownBy(() -> version.setEffectiveInterval(JAN_1, DEC_1))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ACTIVATED");
        assertThatThrownBy(() -> version.addEntitlement(feature, EntitlementMode.DISABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ACTIVATED");
        assertThatThrownBy(() -> version.updateEntitlement(feature.id(), EntitlementMode.DISABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ACTIVATED");
        assertThatThrownBy(() -> version.removeEntitlement(feature.id()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ACTIVATED");
    }

    @Test
    void duplicateFeatureEntitlementRejected() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);

        version.addEntitlement(feature, EntitlementMode.ENABLED, null);

        assertThatThrownBy(() -> version.addEntitlement(feature, EntitlementMode.DISABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("already contains feature");
    }

    @Test
    void featureFromAnotherProductRejected() {
        Product productA = Product.create(BusinessKey.of("product-a"), "Product A");
        Product productB = Product.create(BusinessKey.of("product-b"), "Product B");
        Feature foreignFeature = Feature.create(productB, BusinessKey.of("api_calls"), "API Calls");
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Contract contract = Contract.create(tenant, productA, BusinessKey.of("acme-product-a"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);

        assertThatThrownBy(() -> version.addEntitlement(foreignFeature, EntitlementMode.ENABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void planFromAnotherProductCannotInitializeContractVersion() {
        Product productA = Product.create(BusinessKey.of("product-a"), "Product A");
        Product productB = Product.create(BusinessKey.of("product-b"), "Product B");
        Plan plan = Plan.createDraft(productB, BusinessKey.of("enterprise-2026"), "Enterprise");
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Contract contract = Contract.create(tenant, productA, BusinessKey.of("acme-product-a"));

        assertThatThrownBy(() -> ContractVersion.createDraftFromPlan(contract, plan, 1, JAN_1, JUN_1))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("contract product");
    }

    @Test
    void planSnapshotCopiesEntitlementsRatherThanLivePlanDependency() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise");
        plan.addFeature(feature, EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(100));
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        ContractVersion version = ContractVersion.createDraftFromPlan(contract, plan, 1, JAN_1, JUN_1);
        assertThat(version.sourcePlanId()).contains(plan.id());
        assertThat(version.entitlements()).hasSize(1);
        assertThat(version.entitlements().getFirst().limitConfiguration())
                .contains(LimitConfiguration.ofMaxQuantity(100));

        plan.updateFeature(feature.id(), EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(999));
        assertThat(version.entitlements().getFirst().limitConfiguration())
                .contains(LimitConfiguration.ofMaxQuantity(100));
    }

    @Test
    void overlappingActivatedVersionsRejected() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        ContractVersion v1 = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);
        v1.activate(Instant.parse("2025-12-01T00:00:00Z"), List.of());

        ContractVersion v2 = ContractVersion.createDraft(contract, 2, Instant.parse("2026-03-01T00:00:00Z"), DEC_1);

        assertThatThrownBy(() -> v2.activate(Instant.parse("2026-02-01T00:00:00Z"), List.of(v1)))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void adjacentHalfOpenIntervalsAllowed() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        ContractVersion v1 = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);
        v1.activate(Instant.parse("2025-12-01T00:00:00Z"), List.of());

        ContractVersion v2 = ContractVersion.createDraft(contract, 2, JUN_1, null);
        v2.activate(Instant.parse("2026-01-01T00:00:00Z"), List.of(v1));

        assertThat(v1.isEffectiveAt(Instant.parse("2026-05-31T23:59:59Z"))).isTrue();
        assertThat(v1.isEffectiveAt(JUN_1)).isFalse();
        assertThat(v2.isEffectiveAt(JUN_1)).isTrue();
    }

    @Test
    void effectiveVersionResolutionAtBoundaries() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        ContractVersion v1 = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);
        v1.activate(Instant.parse("2025-12-01T00:00:00Z"), List.of());
        ContractVersion v2 = ContractVersion.createDraft(contract, 2, JUN_1, null);
        v2.activate(Instant.parse("2026-01-01T00:00:00Z"), List.of(v1));

        assertThat(v1.isEffectiveAt(Instant.parse("2026-05-31T23:59:59Z"))).isTrue();
        assertThat(v1.isEffectiveAt(JUN_1)).isFalse();
        assertThat(v2.isEffectiveAt(JUN_1)).isTrue();
        assertThat(v2.isEffectiveAt(Instant.parse("2026-05-31T23:59:59Z"))).isFalse();
    }

    @Test
    void noVersionEffectiveOutsideConfiguredIntervals() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));

        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);
        version.activate(Instant.parse("2025-12-01T00:00:00Z"), List.of());

        assertThat(version.isEffectiveAt(Instant.parse("2025-12-31T23:59:59Z"))).isFalse();
        assertThat(version.isEffectiveAt(JUN_1)).isFalse();
    }

    @Test
    void limitedEntitlementRequiresPositiveLimit() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);

        assertThatThrownBy(() -> version.addEntitlement(feature, EntitlementMode.LIMITED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("LIMITED");
    }

    @Test
    void nonLimitedEntitlementMustNotCarryLimit() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");
        Contract contract = Contract.create(tenant, product, BusinessKey.of("acme-datapilot"));
        ContractVersion version = ContractVersion.createDraft(contract, 1, JAN_1, JUN_1);
        LimitConfiguration limit = LimitConfiguration.ofMaxQuantity(10);

        assertThatThrownBy(() -> version.addEntitlement(feature, EntitlementMode.ENABLED, limit))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("must not carry a limit");
    }
}
