package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.controlplane.application.catalogue.FeatureRepository;
import io.usagecore.controlplane.application.catalogue.PlanRepository;
import io.usagecore.controlplane.application.catalogue.ProductRepository;
import io.usagecore.controlplane.application.catalogue.TenantRepository;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.LimitConfiguration;
import io.usagecore.controlplane.domain.catalogue.Plan;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CatalogueSchemaConstraintTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private FeatureRepository featureRepository;

    @Autowired
    private PlanRepository planRepository;

    @Test
    void flywayMigrationCreatesCatalogueTablesOnRealPostgres() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"
        );
        assertThat(migrations).extracting(row -> row.get("version")).contains("1", "2");
        assertThat(migrations).allMatch(row -> Boolean.TRUE.equals(row.get("success")));

        Integer tenantTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'tenant'",
                Integer.class
        );
        assertThat(tenantTable).isEqualTo(1);
    }

    @Test
    void jpaMappingsRoundTripThroughRepositories() {
        Tenant tenant = tenantRepository.save(Tenant.create(BusinessKey.of("acme"), "Acme Corp"));
        Product product = productRepository.save(
                Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud")
        );
        Feature feature = featureRepository.save(
                Feature.create(product, BusinessKey.of("scheduled_exports"), "Scheduled Exports")
        );
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");
        plan.addFeature(feature, EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(25));
        planRepository.save(plan);

        assertThat(tenantRepository.findByTenantKey("acme")).isPresent();
        assertThat(productRepository.findByProductKey("datapilot-cloud")).isPresent();
        assertThat(featureRepository.findByProductIdAndFeatureKey(product.id(), "scheduled_exports"))
                .isPresent();
        Plan loaded = planRepository.findById(plan.id()).orElseThrow();
        assertThat(loaded.planFeatures()).hasSize(1);
        assertThat(loaded.planFeatures().getFirst().entitlementMode()).isEqualTo(EntitlementMode.LIMITED);
        assertThat(loaded.planFeatures().getFirst().limitConfiguration())
                .contains(LimitConfiguration.ofMaxQuantity(25));
        assertThat(tenant.id()).isNotNull();
    }

    @Test
    void duplicateTenantKeyRejected() {
        insertTenant("tenant-dup-a", "acme-dup", "Acme A");
        assertThatThrownBy(() -> insertTenant("tenant-dup-b", "acme-dup", "Acme B"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateProductKeyRejected() {
        insertProduct("product-dup-a", "product-dup", "Product A");
        assertThatThrownBy(() -> insertProduct("product-dup-b", "product-dup", "Product B"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateFeatureKeyWithinProductRejected_butAllowedAcrossProducts() {
        UUID productA = insertProduct("feat-prod-a", "feat-scope-a", "Scope A");
        UUID productB = insertProduct("feat-prod-b", "feat-scope-b", "Scope B");

        insertFeature("feat-a1", productA, "shared_key", "Feature A");
        assertThatThrownBy(() -> insertFeature("feat-a2", productA, "shared_key", "Feature A2"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertFeature("feat-b1", productB, "shared_key", "Feature B");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feature WHERE feature_key = 'shared_key'",
                Integer.class
        );
        assertThat(count).isEqualTo(2);
    }

    @Test
    void duplicatePlanKeyWithinProductRejected() {
        UUID productId = insertProduct("plan-prod", "plan-scope", "Plan Scope");
        insertPlan("plan-a", productId, "enterprise-2026", "Enterprise A");
        assertThatThrownBy(() -> insertPlan("plan-b", productId, "enterprise-2026", "Enterprise B"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicatePlanFeatureRejected() {
        UUID productId = insertProduct("pf-prod", "pf-scope", "PF Scope");
        UUID featureId = insertFeature("pf-feat", productId, "scheduled_exports", "Exports");
        UUID planId = insertPlan("pf-plan", productId, "starter-2026", "Starter");

        insertPlanFeature("pf-1", planId, featureId, "ENABLED", null);
        assertThatThrownBy(() -> insertPlanFeature("pf-2", planId, featureId, "DISABLED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertTenant(String idSuffix, String tenantKey, String displayName) {
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO tenant (id, tenant_key, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                uuid(idSuffix),
                tenantKey,
                displayName,
                now,
                now
        );
    }

    private UUID insertProduct(String idSuffix, String productKey, String name) {
        UUID id = uuid(idSuffix);
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO product (id, product_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                productKey,
                name,
                now,
                now
        );
        return id;
    }

    private UUID insertFeature(String idSuffix, UUID productId, String featureKey, String name) {
        UUID id = uuid(idSuffix);
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO feature (id, product_id, feature_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                productId,
                featureKey,
                name,
                now,
                now
        );
        return id;
    }

    private UUID insertPlan(String idSuffix, UUID productId, String planKey, String name) {
        UUID id = uuid(idSuffix);
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO plan (id, product_id, plan_key, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?)
                """,
                id,
                productId,
                planKey,
                name,
                now,
                now
        );
        return id;
    }

    private void insertPlanFeature(
            String idSuffix,
            UUID planId,
            UUID featureId,
            String mode,
            Long limitQuantity
    ) {
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO plan_feature (
                    id, plan_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                uuid(idSuffix),
                planId,
                featureId,
                mode,
                limitQuantity,
                now,
                now
        );
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static UUID uuid(String suffix) {
        return UUID.nameUUIDFromBytes(("usagecore-catalogue-" + suffix).getBytes());
    }
}
