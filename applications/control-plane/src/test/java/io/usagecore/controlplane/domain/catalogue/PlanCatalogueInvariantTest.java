package io.usagecore.controlplane.domain.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlanCatalogueInvariantTest {

    @Test
    void rejectsBlankProductAndFeatureNames() {
        assertThatThrownBy(() -> Product.create(BusinessKey.of("datapilot-cloud"), " "))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("name");

        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        assertThatThrownBy(() -> Feature.create(product, BusinessKey.of("scheduled_exports"), ""))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsDuplicateFeatureOnSamePlan() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("scheduled_exports"), "Scheduled Exports");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");

        plan.addFeature(feature, EntitlementMode.ENABLED, null);

        assertThatThrownBy(() -> plan.addFeature(feature, EntitlementMode.DISABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("already contains feature");
    }

    @Test
    void featureAssertBelongsToProductRejectsWrongProduct() {
        Product productA = Product.create(BusinessKey.of("product-a"), "Product A");
        Product productB = Product.create(BusinessKey.of("product-b"), "Product B");
        Feature feature = Feature.create(productA, BusinessKey.of("scheduled_exports"), "Scheduled Exports");

        assertThatThrownBy(() -> feature.assertBelongsToProduct(productB.id()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void planFeatureCannotReferenceFeatureFromAnotherProduct() {
        Product productA = Product.create(BusinessKey.of("product-a"), "Product A");
        Product productB = Product.create(BusinessKey.of("product-b"), "Product B");
        Feature foreignFeature = Feature.create(productB, BusinessKey.of("scheduled_exports"), "Scheduled Exports");
        Plan plan = Plan.createDraft(productA, BusinessKey.of("enterprise-2026"), "Enterprise 2026");

        assertThatThrownBy(() -> plan.addFeature(foreignFeature, EntitlementMode.ENABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void limitedRequiresPositiveLimitConfiguration() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");

        assertThatThrownBy(() -> plan.addFeature(feature, EntitlementMode.LIMITED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("LIMITED");

        assertThatThrownBy(() -> LimitConfiguration.ofMaxQuantity(0))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> LimitConfiguration.ofMaxQuantity(-5))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void nonLimitedCannotCarryLimitConfiguration() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("scheduled_exports"), "Scheduled Exports");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");
        LimitConfiguration limit = LimitConfiguration.ofMaxQuantity(10);

        assertThatThrownBy(() -> plan.addFeature(feature, EntitlementMode.ENABLED, limit))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("must not carry a limit");

        assertThatThrownBy(() -> plan.addFeature(feature, EntitlementMode.DISABLED, limit))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("must not carry a limit");
    }

    @Test
    void limitedAcceptsValidPositiveLimit() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");

        PlanFeature planFeature = plan.addFeature(
                feature,
                EntitlementMode.LIMITED,
                LimitConfiguration.ofMaxQuantity(100)
        );

        assertThat(planFeature.entitlementMode()).isEqualTo(EntitlementMode.LIMITED);
        assertThat(planFeature.limitConfiguration()).contains(LimitConfiguration.ofMaxQuantity(100));
    }

    @Test
    void publishedPlanCommercialConfigurationCannotBeMutated() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature exports = Feature.create(product, BusinessKey.of("scheduled_exports"), "Scheduled Exports");
        Feature apiCalls = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");
        plan.addFeature(exports, EntitlementMode.ENABLED, null);
        plan.publish();

        assertThat(plan.status()).isEqualTo(PlanStatus.PUBLISHED);
        assertThatThrownBy(() -> plan.addFeature(apiCalls, EntitlementMode.ENABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("PUBLISHED");
        assertThatThrownBy(() -> plan.updateFeature(exports.id(), EntitlementMode.DISABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("PUBLISHED");
        assertThatThrownBy(() -> plan.removeFeature(exports.id()))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("PUBLISHED");
        assertThatThrownBy(() -> plan.rename("Renamed"))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    void archivedPlanCannotBeModified() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("scheduled_exports"), "Scheduled Exports");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");
        plan.addFeature(feature, EntitlementMode.ENABLED, null);
        plan.publish();
        plan.archive();

        assertThat(plan.status()).isEqualTo(PlanStatus.ARCHIVED);
        assertThatThrownBy(() -> plan.rename("Renamed"))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ARCHIVED");
        assertThatThrownBy(() -> plan.addFeature(feature, EntitlementMode.DISABLED, null))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ARCHIVED");
        assertThatThrownBy(plan::publish)
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    void draftPlanConfigurationMayChange() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        Feature feature = Feature.create(product, BusinessKey.of("api_calls"), "API Calls");
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");

        plan.addFeature(feature, EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(50));
        plan.updateFeature(feature.id(), EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(200));
        plan.rename("Enterprise 2026 Revised");

        assertThat(plan.name()).isEqualTo("Enterprise 2026 Revised");
        assertThat(plan.planFeatures()).hasSize(1);
        assertThat(plan.planFeatures().getFirst().limitConfiguration())
                .contains(LimitConfiguration.ofMaxQuantity(200));

        plan.removeFeature(feature.id());
        assertThat(plan.planFeatures()).isEmpty();
    }
}
