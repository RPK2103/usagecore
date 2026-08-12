package io.usagecore.entitlementruntime.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.usagecore.entitlementruntime.domain.EntitlementReasonCodes;
import io.usagecore.entitlementruntime.support.CommercialFixtureSeeder;
import io.usagecore.entitlementruntime.support.FixedClockTestConfiguration;
import io.usagecore.entitlementruntime.support.TestJwtSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EntitlementCheckApiIntegrationTest extends AbstractRuntimeApiIntegrationTest {

    private static final Instant EVAL = FixedClockTestConfiguration.FIXED_INSTANT;
    private static final Instant PAST = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2026-12-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CommercialFixtureSeeder seeder;
    private UUID acmeTenantId;
    private UUID globexTenantId;

    @BeforeEach
    void setUpFixtures() {
        jdbcTemplate.update("DELETE FROM entitlement_decision");
        jdbcTemplate.update("DELETE FROM entitlement");
        jdbcTemplate.update("DELETE FROM contract_version");
        jdbcTemplate.update("DELETE FROM contract");
        jdbcTemplate.update("DELETE FROM plan_feature");
        jdbcTemplate.update("DELETE FROM plan");
        jdbcTemplate.update("DELETE FROM feature");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM tenant");

        seeder = new CommercialFixtureSeeder(jdbcTemplate);
        acmeTenantId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        globexTenantId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        seeder.ensureTenant(acmeTenantId, "acme");
        seeder.ensureTenant(globexTenantId, "globex");
        seeder.ensureProductAndFeature();
    }

    @Test
    void noAuthentication_returns401() {
        givenUnauthenticatedJson()
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(401)
                .body("errorCode", equalTo("UNAUTHORIZED"));
    }

    @Test
    void unknownTenantIdField_returns400() {
        givenBearer(developerToken(acmeTenantId))
                .body(Map.of(
                        "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                        "featureKey", CommercialFixtureSeeder.FEATURE_KEY,
                        "requestedUnits", 1,
                        "tenantId", globexTenantId.toString()
                ))
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void requestedUnitsNonPositive_returns400() {
        givenBearer(developerToken(acmeTenantId))
                .body(Map.of(
                        "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                        "featureKey", CommercialFixtureSeeder.FEATURE_KEY,
                        "requestedUnits", 0
                ))
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void enabledEntitlement_returnsAllow() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 1, PAST, null, "ENABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .header("X-Correlation-Id", "corr-enabled")
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW"))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_ENABLED))
                .body("contractVersion", equalTo(1))
                .body("correlationId", equalTo("corr-enabled"))
                .body("configuredLimit", nullValue());

        assertDecisionPersisted(acmeTenantId, "ALLOW", EntitlementReasonCodes.ENTITLEMENT_ENABLED, 1);
    }

    @Test
    void disabledEntitlement_returnsDeny() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 1, PAST, null, "DISABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_DISABLED));

        assertDecisionPersisted(acmeTenantId, "DENY", EntitlementReasonCodes.ENTITLEMENT_DISABLED, 1);
    }

    @Test
    void limitedEntitlement_returnsAllowWithLimit() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 3, PAST, null, "LIMITED", 1_000_000L
        );

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW_WITH_LIMIT"))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_LIMITED))
                .body("configuredLimit", equalTo(1_000_000))
                .body("contractVersion", equalTo(3));

        assertDecisionPersisted(acmeTenantId, "ALLOW_WITH_LIMIT", EntitlementReasonCodes.ENTITLEMENT_LIMITED, 3);
    }

    @Test
    void requestedUnitsExceedsConfiguredLimit_returnsDeny() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 1, PAST, null, "LIMITED", 10L
        );

        givenBearer(developerToken(acmeTenantId))
                .body(Map.of(
                        "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                        "featureKey", CommercialFixtureSeeder.FEATURE_KEY,
                        "requestedUnits", 11
                ))
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.REQUEST_EXCEEDS_CONTRACT_LIMIT))
                .body("configuredLimit", equalTo(10));
    }

    @Test
    void noContract_returnsDenyNoActiveEntitlement() {
        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT))
                .body("contractVersion", nullValue());

        assertDecisionPersisted(acmeTenantId, "DENY", EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT, null);
    }

    @Test
    void noEffectiveContractVersion_returnsDeny() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 1, PAST, EVAL, "ENABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT));
    }

    @Test
    void featureNotEntitled_returnsDeny() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 1, PAST, null, "ENABLED", null
        );
        jdbcTemplate.update("DELETE FROM entitlement");

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT));
    }

    @Test
    void futureDatedActivatedVersion_doesNotApplyEarly() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 1, FUTURE, null, "ENABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT));
    }

    @Test
    void halfOpenInterval_includesEffectiveFrom_excludesEffectiveUntil() {
        Instant until = EVAL;
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-closed", 1, PAST, until, "ENABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("reason", equalTo(EntitlementReasonCodes.NO_ACTIVE_ENTITLEMENT));

        jdbcTemplate.update("DELETE FROM entitlement");
        jdbcTemplate.update("DELETE FROM contract_version");
        jdbcTemplate.update("DELETE FROM contract");

        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-open", 1, EVAL, null, "ENABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW"))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_ENABLED));
    }

    @Test
    void planChangesDoNotInfluenceActivatedContractVersionDecision() {
        UUID productId = seeder.ensureProductAndFeature();
        UUID planId = seeder.seedPlanWithFeature(productId, "starter", "LIMITED", 100L);
        var activated = seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 2, PAST, null, "LIMITED", 100L
        );
        jdbcTemplate.update(
                "UPDATE contract_version SET source_plan_id = ? WHERE id = ?",
                planId,
                activated.contractVersionId()
        );

        seeder.updatePlanFeatureMode(planId, "DISABLED", null);

        givenBearer(developerToken(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW_WITH_LIMIT"))
                .body("configuredLimit", equalTo(100))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_LIMITED));
    }

    @Test
    void dataPilotAcmeAndGlobex_tenantIsolationFromJwtOnly() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 3, PAST, null, "LIMITED", 1_000_000L
        );
        seeder.seedActivatedEntitlement(
                globexTenantId, "globex-dp", 1, PAST, null, "DISABLED", null
        );

        String acmeToken = developerToken(acmeTenantId);
        String globexToken = developerToken(globexTenantId);

        givenBearer(acmeToken)
                .header("X-Correlation-Id", "datapilot-acme")
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW_WITH_LIMIT"))
                .body("configuredLimit", equalTo(1_000_000))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_LIMITED));

        givenBearer(globexToken)
                .header("X-Correlation-Id", "datapilot-globex")
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("DENY"))
                .body("reason", equalTo(EntitlementReasonCodes.ENTITLEMENT_DISABLED));

        Integer acmeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entitlement_decision WHERE tenant_id = ? AND correlation_id = ?",
                Integer.class,
                acmeTenantId,
                "datapilot-acme"
        );
        Integer globexRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM entitlement_decision WHERE tenant_id = ? AND correlation_id = ?",
                Integer.class,
                globexTenantId,
                "datapilot-globex"
        );
        assertThat(acmeRows).isEqualTo(1);
        assertThat(globexRows).isEqualTo(1);

        givenBearer(acmeToken)
                .body(Map.of(
                        "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                        "featureKey", CommercialFixtureSeeder.FEATURE_KEY,
                        "tenantId", globexTenantId.toString()
                ))
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(400);
    }

    @Test
    void developerWithoutTenant_returns403() {
        givenBearer(TestJwtSupport.developerWithoutTenant())
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(403);
    }

    @Test
    void auditorRole_returns403() {
        givenBearer(TestJwtSupport.auditor(acmeTenantId))
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(403);
    }

    @Test
    void persistedDecisionContainsCorrelationAndContractVersionEvidence() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp", 7, PAST, null, "ENABLED", null
        );

        givenBearer(developerToken(acmeTenantId))
                .header("X-Correlation-Id", "evidence-corr")
                .body(checkBody())
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT correlation_id, contract_version_number, contract_id, contract_version_id "
                        + "FROM entitlement_decision WHERE tenant_id = ?",
                acmeTenantId
        );
        assertThat(row.get("correlation_id")).isEqualTo("evidence-corr");
        assertThat(row.get("contract_version_number")).isEqualTo(7);
        assertThat(row.get("contract_id")).isNotNull();
        assertThat(row.get("contract_version_id")).isNotNull();
    }

    private static Map<String, Object> checkBody() {
        return Map.of(
                "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                "featureKey", CommercialFixtureSeeder.FEATURE_KEY
        );
    }

    private void assertDecisionPersisted(
            UUID tenantId,
            String decision,
            String reason,
            Integer contractVersionNumber
    ) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT decision, reason, contract_version_number, correlation_id "
                        + "FROM entitlement_decision WHERE tenant_id = ?",
                tenantId
        );
        assertThat(row.get("decision")).isEqualTo(decision);
        assertThat(row.get("reason")).isEqualTo(reason);
        assertThat(row.get("contract_version_number")).isEqualTo(contractVersionNumber);
        assertThat(row.get("correlation_id")).isNotNull();
    }
}
