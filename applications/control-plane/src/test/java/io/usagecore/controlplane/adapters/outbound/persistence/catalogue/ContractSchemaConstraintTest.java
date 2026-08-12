package io.usagecore.controlplane.adapters.outbound.persistence.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.usagecore.controlplane.application.catalogue.ContractApplicationService;
import io.usagecore.controlplane.application.catalogue.ContractRepository;
import io.usagecore.controlplane.application.catalogue.ContractVersionApplicationService;
import io.usagecore.controlplane.application.catalogue.ContractVersionRepository;
import io.usagecore.controlplane.application.catalogue.FeatureRepository;
import io.usagecore.controlplane.application.catalogue.PlanRepository;
import io.usagecore.controlplane.application.catalogue.ProductRepository;
import io.usagecore.controlplane.application.catalogue.TenantRepository;
import io.usagecore.controlplane.domain.catalogue.BusinessKey;
import io.usagecore.controlplane.domain.catalogue.Contract;
import io.usagecore.controlplane.domain.catalogue.ContractVersion;
import io.usagecore.controlplane.domain.catalogue.ContractVersionStatus;
import io.usagecore.controlplane.domain.catalogue.DomainInvariantException;
import io.usagecore.controlplane.domain.catalogue.EntitlementMode;
import io.usagecore.controlplane.domain.catalogue.Feature;
import io.usagecore.controlplane.domain.catalogue.LimitConfiguration;
import io.usagecore.controlplane.domain.catalogue.Plan;
import io.usagecore.controlplane.domain.catalogue.Product;
import io.usagecore.controlplane.domain.catalogue.Tenant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class ContractSchemaConstraintTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN_1 = Instant.parse("2026-06-01T00:00:00Z");

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

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ContractVersionRepository contractVersionRepository;

    @Autowired
    private ContractApplicationService contractApplicationService;

    @Autowired
    private ContractVersionApplicationService contractVersionApplicationService;

    @Test
    void flywayMigrationCreatesContractTablesOnRealPostgres() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"
        );
        assertThat(migrations).extracting(row -> row.get("version")).contains("1", "2", "3");
        assertThat(migrations).allMatch(row -> Boolean.TRUE.equals(row.get("success")));

        Integer contractTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'contract'",
                Integer.class
        );
        assertThat(contractTable).isEqualTo(1);

        Integer exclusionConstraint = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ex_contract_version_activated_no_overlap'
                """,
                Integer.class
        );
        assertThat(exclusionConstraint).isEqualTo(1);
    }

    @Test
    void jpaMappingsRoundTripThroughRepositories() {
        Tenant tenant = tenantRepository.save(Tenant.create(BusinessKey.of("acme"), "Acme Corp"));
        Product product = productRepository.save(
                Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud")
        );
        Feature feature = featureRepository.save(
                Feature.create(product, BusinessKey.of("api_calls"), "API Calls")
        );
        Contract contract = contractApplicationService.createContract(
                tenant,
                product,
                BusinessKey.of("acme-datapilot")
        );
        Plan plan = Plan.createDraft(product, BusinessKey.of("enterprise-2026"), "Enterprise 2026");
        plan.addFeature(feature, EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(100));
        planRepository.save(plan);

        ContractVersion version = contractVersionApplicationService.createDraftFromPlan(
                contract.id(),
                plan.id(),
                JAN_1,
                JUN_1
        );
        version = contractVersionApplicationService.updateDraftEntitlement(
                version.id(),
                feature.id(),
                EntitlementMode.LIMITED,
                LimitConfiguration.ofMaxQuantity(150)
        );
        version = contractVersionApplicationService.activateVersion(version.id());

        ContractVersion loaded = contractVersionRepository.findById(version.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(ContractVersionStatus.ACTIVATED);
        assertThat(loaded.entitlements()).hasSize(1);
        assertThat(loaded.entitlements().getFirst().limitConfiguration())
                .contains(LimitConfiguration.ofMaxQuantity(150));
        assertThat(loaded.sourcePlanId()).contains(plan.id());

        assertThat(contractVersionApplicationService.resolveEffectiveVersion(
                contract.id(),
                Instant.parse("2026-03-01T00:00:00Z")
        )).map(ContractVersion::id).contains(version.id());
    }

    @Test
    void duplicateTenantProductContractRejected() {
        UUID tenantId = insertTenant("contract-dup-tenant", "contract-dup-tenant-key", "Tenant");
        UUID productId = insertProduct("contract-dup-product", "contract-dup-product-key", "Product");

        insertContract("contract-a", tenantId, productId, "contract-key-a");
        assertThatThrownBy(() -> insertContract("contract-b", tenantId, productId, "contract-key-b"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateContractKeyWithinTenantRejected() {
        UUID tenantId = insertTenant("contract-key-tenant", "contract-key-tenant-key", "Tenant");
        UUID productA = insertProduct("contract-key-prod-a", "contract-key-prod-a", "Product A");
        UUID productB = insertProduct("contract-key-prod-b", "contract-key-prod-b", "Product B");

        insertContract("contract-key-a", tenantId, productA, "shared-contract-key");
        assertThatThrownBy(() -> insertContract("contract-key-b", tenantId, productB, "shared-contract-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateVersionNumberWithinContractRejected() {
        UUID tenantId = insertTenant("version-dup-tenant", "version-dup-tenant-key", "Tenant");
        UUID productId = insertProduct("version-dup-product", "version-dup-product-key", "Product");
        UUID contractId = insertContract("version-dup-contract", tenantId, productId, "version-dup-key");

        insertContractVersion("version-1", contractId, tenantId, 1, "DRAFT", JAN_1, JUN_1, null);
        assertThatThrownBy(() ->
                insertContractVersion("version-2", contractId, tenantId, 1, "DRAFT", JUN_1, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonPositiveVersionNumberRejected() {
        UUID tenantId = insertTenant("version-pos-tenant", "version-pos-tenant-key", "Tenant");
        UUID productId = insertProduct("version-pos-product", "version-pos-product-key", "Product");
        UUID contractId = insertContract("version-pos-contract", tenantId, productId, "version-pos-key");

        assertThatThrownBy(() ->
                insertContractVersion("version-zero", contractId, tenantId, 0, "DRAFT", JAN_1, JUN_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidEffectiveIntervalRejectedAtDatabase() {
        UUID tenantId = insertTenant("interval-tenant", "interval-tenant-key", "Tenant");
        UUID productId = insertProduct("interval-product", "interval-product-key", "Product");
        UUID contractId = insertContract("interval-contract", tenantId, productId, "interval-key");

        assertThatThrownBy(() ->
                insertContractVersion("interval-bad", contractId, tenantId, 1, "DRAFT", JUN_1, JAN_1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void overlappingActivatedVersionsRejectedByDatabase() {
        UUID tenantId = insertTenant("overlap-tenant", "overlap-tenant-key", "Tenant");
        UUID productId = insertProduct("overlap-product", "overlap-product-key", "Product");
        UUID contractId = insertContract("overlap-contract", tenantId, productId, "overlap-key");
        Instant activatedAt = Instant.parse("2025-12-01T00:00:00Z");

        insertContractVersion(
                "overlap-v1",
                contractId,
                tenantId,
                1,
                "ACTIVATED",
                JAN_1,
                JUN_1,
                activatedAt
        );

        assertThatThrownBy(() ->
                insertContractVersion(
                        "overlap-v2",
                        contractId,
                        tenantId,
                        2,
                        "ACTIVATED",
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-12-01T00:00:00Z"),
                        activatedAt
                ))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adjacentActivatedIntervalsAllowedAtDatabase() {
        UUID tenantId = insertTenant("adjacent-tenant", "adjacent-tenant-key", "Tenant");
        UUID productId = insertProduct("adjacent-product", "adjacent-product-key", "Product");
        UUID contractId = insertContract("adjacent-contract", tenantId, productId, "adjacent-key");
        Instant activatedAt = Instant.parse("2025-12-01T00:00:00Z");

        insertContractVersion("adjacent-v1", contractId, tenantId, 1, "ACTIVATED", JAN_1, JUN_1, activatedAt);
        insertContractVersion("adjacent-v2", contractId, tenantId, 2, "ACTIVATED", JUN_1, null, activatedAt);
    }

    @Test
    void duplicateEntitlementFeatureWithinVersionRejected() {
        UUID tenantId = insertTenant("ent-dup-tenant", "ent-dup-tenant-key", "Tenant");
        UUID productId = insertProduct("ent-dup-product", "ent-dup-product-key", "Product");
        UUID featureId = insertFeature("ent-dup-feature", productId, "api_calls", "API Calls");
        UUID contractId = insertContract("ent-dup-contract", tenantId, productId, "ent-dup-key");
        UUID versionId = insertContractVersion(
                "ent-dup-version",
                contractId,
                tenantId,
                1,
                "DRAFT",
                JAN_1,
                JUN_1,
                null
        );

        insertEntitlement("ent-1", versionId, featureId, "ENABLED", null);
        assertThatThrownBy(() -> insertEntitlement("ent-2", versionId, featureId, "DISABLED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void effectiveVersionResolutionAtBoundariesThroughRepository() {
        Tenant tenant = tenantRepository.save(Tenant.create(BusinessKey.of("boundary-tenant"), "Boundary Tenant"));
        Product product = productRepository.save(
                Product.create(BusinessKey.of("boundary-product"), "Boundary Product")
        );
        Contract contract = contractApplicationService.createContract(
                tenant,
                product,
                BusinessKey.of("boundary-contract")
        );

        ContractVersion v1 = contractVersionApplicationService.createDraftVersion(contract.id(), JAN_1, JUN_1);
        contractVersionApplicationService.activateVersion(v1.id());
        ContractVersion v2 = contractVersionApplicationService.createDraftVersion(contract.id(), JUN_1, null);
        contractVersionApplicationService.activateVersion(v2.id());

        assertThat(contractVersionApplicationService.resolveEffectiveVersion(
                contract.id(),
                Instant.parse("2026-05-31T23:59:59Z")
        ).map(ContractVersion::versionNumber)).contains(1);
        assertThat(contractVersionApplicationService.resolveEffectiveVersion(
                contract.id(),
                JUN_1
        ).map(ContractVersion::versionNumber)).contains(2);
        assertThat(contractVersionApplicationService.resolveEffectiveVersion(
                contract.id(),
                Instant.parse("2025-12-31T23:59:59Z")
        )).isEmpty();
    }

    @Test
    void firstDraftVersionNumberIsOne() {
        Tenant tenant = tenantRepository.save(Tenant.create(BusinessKey.of("version-one-tenant"), "Version One"));
        Product product = productRepository.save(
                Product.create(BusinessKey.of("version-one-product"), "Version One Product")
        );
        Contract contract = contractApplicationService.createContract(
                tenant,
                product,
                BusinessKey.of("version-one-contract")
        );

        ContractVersion version = contractVersionApplicationService.createDraftVersion(
                contract.id(),
                JAN_1,
                JUN_1
        );

        assertThat(version.versionNumber()).isEqualTo(1);
    }

    @Test
    void subsequentDraftVersionNumberIncrementsToTwo() {
        Tenant tenant = tenantRepository.save(Tenant.create(BusinessKey.of("version-two-tenant"), "Version Two"));
        Product product = productRepository.save(
                Product.create(BusinessKey.of("version-two-product"), "Version Two Product")
        );
        Contract contract = contractApplicationService.createContract(
                tenant,
                product,
                BusinessKey.of("version-two-contract")
        );

        contractVersionApplicationService.createDraftVersion(contract.id(), JAN_1, JUN_1);
        ContractVersion second = contractVersionApplicationService.createDraftVersion(
                contract.id(),
                JUN_1,
                null
        );

        assertThat(second.versionNumber()).isEqualTo(2);
    }

    @Test
    void applicationServiceAssignsSequentialVersionNumbersNotGaps() {
        Tenant tenant = tenantRepository.save(
                Tenant.create(BusinessKey.of("version-seq-tenant"), "Version Sequential")
        );
        Product product = productRepository.save(
                Product.create(BusinessKey.of("version-seq-product"), "Version Sequential Product")
        );
        Contract contract = contractApplicationService.createContract(
                tenant,
                product,
                BusinessKey.of("version-seq-contract")
        );

        ContractVersion first = contractVersionApplicationService.createDraftVersion(
                contract.id(),
                JAN_1,
                JUN_1
        );
        ContractVersion second = contractVersionApplicationService.createDraftFromPlan(
                contract.id(),
                createDraftPlanWithFeature(product).id(),
                JUN_1,
                null
        );

        assertThat(first.versionNumber()).isEqualTo(1);
        assertThat(second.versionNumber()).isEqualTo(2);
        assertThat(contractVersionRepository.findByContractId(contract.id()))
                .extracting(ContractVersion::versionNumber)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void planSnapshotRemainsUnchangedAfterPlanMutation() {
        Tenant tenant = tenantRepository.save(
                Tenant.create(BusinessKey.of("snapshot-tenant"), "Snapshot Tenant")
        );
        Product product = productRepository.save(
                Product.create(BusinessKey.of("snapshot-product"), "Snapshot Product")
        );
        Feature feature = featureRepository.save(
                Feature.create(product, BusinessKey.of("api_calls"), "API Calls")
        );
        Contract contract = contractApplicationService.createContract(
                tenant,
                product,
                BusinessKey.of("snapshot-contract")
        );
        Plan plan = Plan.createDraft(product, BusinessKey.of("snapshot-plan"), "Snapshot Plan");
        plan.addFeature(feature, EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(100));
        planRepository.save(plan);

        ContractVersion version = contractVersionApplicationService.createDraftFromPlan(
                contract.id(),
                plan.id(),
                JAN_1,
                JUN_1
        );
        UUID versionId = version.id();

        plan.updateFeature(feature.id(), EntitlementMode.LIMITED, LimitConfiguration.ofMaxQuantity(999));
        planRepository.save(plan);

        ContractVersion reloaded = contractVersionRepository.findById(versionId).orElseThrow();
        assertThat(reloaded.entitlements()).hasSize(1);
        assertThat(reloaded.entitlements().getFirst().limitConfiguration())
                .contains(LimitConfiguration.ofMaxQuantity(100));
        assertThat(reloaded.entitlements().getFirst().entitlementMode())
                .isEqualTo(EntitlementMode.LIMITED);
    }

    @Test
    void multipleEffectiveVersionsFailWithInvariantError() {
        UUID tenantId = insertTenant("multi-eff-tenant", "multi-eff-tenant-key", "Tenant");
        UUID productId = insertProduct("multi-eff-product", "multi-eff-product-key", "Product");
        UUID contractId = insertContract("multi-eff-contract", tenantId, productId, "multi-eff-key");
        Instant activatedAt = Instant.parse("2025-12-01T00:00:00Z");
        Instant probe = Instant.parse("2026-03-01T00:00:00Z");

        jdbcTemplate.execute(
                "ALTER TABLE contract_version DROP CONSTRAINT IF EXISTS ex_contract_version_activated_no_overlap"
        );
        try {
            insertContractVersion(
                    "multi-eff-v1",
                    contractId,
                    tenantId,
                    1,
                    "ACTIVATED",
                    JAN_1,
                    JUN_1,
                    activatedAt
            );
            insertContractVersion(
                    "multi-eff-v2",
                    contractId,
                    tenantId,
                    2,
                    "ACTIVATED",
                    Instant.parse("2026-02-01T00:00:00Z"),
                    Instant.parse("2026-12-01T00:00:00Z"),
                    activatedAt
            );

            assertThatThrownBy(() ->
                    contractVersionApplicationService.resolveEffectiveVersion(contractId, probe))
                    .isInstanceOf(DomainInvariantException.class)
                    .hasMessageContaining("Multiple effective contract versions");
        } finally {
            jdbcTemplate.update("DELETE FROM contract_version WHERE contract_id = ?", contractId);
            jdbcTemplate.update("DELETE FROM contract WHERE id = ?", contractId);
            Integer constraintCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conname = 'ex_contract_version_activated_no_overlap'
                    """,
                    Integer.class
            );
            if (constraintCount == 0) {
                jdbcTemplate.execute(
                        """
                        ALTER TABLE contract_version ADD CONSTRAINT ex_contract_version_activated_no_overlap
                        EXCLUDE USING gist (
                            contract_id WITH =,
                            tstzrange(effective_from, effective_until, '[)') WITH &&
                        ) WHERE (status = 'ACTIVATED')
                        """
                );
            }
        }
    }

    @Test
    void concurrentOverlappingActivatedInsertsFailForAtLeastOneConnection() throws Exception {
        UUID tenantId = insertTenant("concurrent-jdbc-tenant", "concurrent-jdbc-tenant-key", "Concurrent");
        UUID productId = insertProduct("concurrent-jdbc-product", "concurrent-jdbc-product-key", "Product");
        UUID contractId = insertContract("concurrent-jdbc-contract", tenantId, productId, "concurrent-jdbc-key");
        Instant activatedAt = Instant.parse("2025-12-01T00:00:00Z");

        insertContractVersion("concurrent-jdbc-v1", contractId, tenantId, 1, "ACTIVATED", JAN_1, JUN_1, activatedAt);

        UUID versionA = uuid("concurrent-jdbc-v2a");
        UUID versionB = uuid("concurrent-jdbc-v2b");
        Instant overlapFromA = JUN_1;
        Instant overlapUntilA = Instant.parse("2026-09-01T00:00:00Z");
        Instant overlapFromB = Instant.parse("2026-06-15T00:00:00Z");
        Instant overlapUntilB = Instant.parse("2026-10-01T00:00:00Z");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> futureA = executor.submit(() -> insertActivatedVersionConcurrently(
                    versionA,
                    contractId,
                    tenantId,
                    2,
                    overlapFromA,
                    overlapUntilA,
                    activatedAt,
                    ready,
                    start,
                    successCount,
                    failureCount
            ));
            Future<?> futureB = executor.submit(() -> insertActivatedVersionConcurrently(
                    versionB,
                    contractId,
                    tenantId,
                    3,
                    overlapFromB,
                    overlapUntilB,
                    activatedAt,
                    ready,
                    start,
                    successCount,
                    failureCount
            ));

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
    }

    private Plan createDraftPlanWithFeature(Product product) {
        Feature feature = featureRepository.save(
                Feature.create(product, BusinessKey.of("version-seq-feature"), "Version Seq Feature")
        );
        Plan plan = Plan.createDraft(product, BusinessKey.of("version-seq-plan"), "Version Seq Plan");
        plan.addFeature(feature, EntitlementMode.ENABLED, null);
        return planRepository.save(plan);
    }

    private void insertActivatedVersionConcurrently(
            UUID versionId,
            UUID contractId,
            UUID tenantId,
            int versionNumber,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant activatedAt,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicInteger successCount,
            AtomicInteger failureCount
    ) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
            Timestamp now = now();
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword()
            )) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO contract_version (
                            id, contract_id, tenant_id, version_number, status,
                            effective_from, effective_until, activated_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'ACTIVATED', ?, ?, ?, ?, ?)
                        """
                )) {
                    statement.setObject(1, versionId);
                    statement.setObject(2, contractId);
                    statement.setObject(3, tenantId);
                    statement.setInt(4, versionNumber);
                    statement.setTimestamp(5, Timestamp.from(effectiveFrom));
                    statement.setTimestamp(6, Timestamp.from(effectiveUntil));
                    statement.setTimestamp(7, Timestamp.from(activatedAt));
                    statement.setTimestamp(8, now);
                    statement.setTimestamp(9, now);
                    statement.executeUpdate();
                }
                connection.commit();
                successCount.incrementAndGet();
            }
        } catch (SQLException | InterruptedException ex) {
            failureCount.incrementAndGet();
        }
    }

    private UUID insertContract(String idSuffix, UUID tenantId, UUID productId, String contractKey) {
        UUID id = uuid(idSuffix);
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO contract (id, tenant_id, product_id, contract_key, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                tenantId,
                productId,
                contractKey,
                now,
                now
        );
        return id;
    }

    private UUID insertContractVersion(
            String idSuffix,
            UUID contractId,
            UUID tenantId,
            int versionNumber,
            String status,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant activatedAt
    ) {
        UUID id = uuid(idSuffix);
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO contract_version (
                    id, contract_id, tenant_id, version_number, status,
                    effective_from, effective_until, activated_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                contractId,
                tenantId,
                versionNumber,
                status,
                Timestamp.from(effectiveFrom),
                effectiveUntil == null ? null : Timestamp.from(effectiveUntil),
                activatedAt == null ? null : Timestamp.from(activatedAt),
                now,
                now
        );
        return id;
    }

    private void insertEntitlement(
            String idSuffix,
            UUID contractVersionId,
            UUID featureId,
            String mode,
            Long limitQuantity
    ) {
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO entitlement (
                    id, contract_version_id, feature_id, entitlement_mode, limit_quantity, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                uuid(idSuffix),
                contractVersionId,
                featureId,
                mode,
                limitQuantity,
                now,
                now
        );
    }

    private UUID insertTenant(String idSuffix, String tenantKey, String displayName) {
        UUID id = uuid(idSuffix);
        Timestamp now = now();
        jdbcTemplate.update(
                """
                INSERT INTO tenant (id, tenant_key, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                tenantKey,
                displayName,
                now,
                now
        );
        return id;
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

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static UUID uuid(String suffix) {
        return UUID.nameUUIDFromBytes(("usagecore-contract-" + suffix).getBytes());
    }
}
