package io.usagecore.entitlementruntime.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.restassured.RestAssured;
import io.usagecore.entitlementruntime.application.observability.EntitlementRuntimeMetrics;
import io.usagecore.entitlementruntime.domain.EntitlementReasonCodes;
import io.usagecore.entitlementruntime.support.CommercialFixtureSeeder;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfigureObservability
class EntitlementObservabilityIntegrationTest extends AbstractRuntimeApiIntegrationTest {

    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "tenantId",
            "eventId",
            "correlationId",
            "idempotencyKey",
            "contractId",
            "principalId",
            "commercialPeriodId",
            "reconciliationRunId",
            "adjustmentId"
    );

    @LocalServerPort
    private int serverPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CommercialFixtureSeeder seeder;
    private UUID acmeTenantId;

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
        seeder.ensureTenant(acmeTenantId, "acme");
        seeder.ensureProductAndFeature();
    }

    @Test
    void healthAndPrometheusArePublic_apiStillRequiresJwt() {
        RestAssured.given().port(serverPort).basePath("").get("/actuator/health")
                .then().statusCode(200).body("status", equalTo("UP"));
        RestAssured.given().port(serverPort).basePath("").get("/actuator/health/readiness")
                .then().statusCode(200).body("status", equalTo("UP"));
        String prometheus = RestAssured.given().port(serverPort).basePath("").get("/actuator/prometheus")
                .then().statusCode(200).extract().asString();
        assertThat(prometheus).contains("jvm_memory_used_bytes");
        RestAssured.given().port(serverPort).basePath("").get("/actuator/env")
                .then().statusCode(HttpStatus.UNAUTHORIZED.value());
        givenUnauthenticatedJson().body(java.util.Map.of(
                "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                "featureKey", CommercialFixtureSeeder.FEATURE_KEY,
                "requestedUnits", 1
        )).when().post("/entitlements/check").then().statusCode(401);
    }

    @Test
    void entitlementDecisionIncrementsBoundedMetric() {
        seeder.seedActivatedEntitlement(
                acmeTenantId, "acme-dp-obs", 1, Instant.parse("2026-01-01T00:00:00Z"), null, "ENABLED", null
        );
        double before = counter(
                EntitlementRuntimeMetrics.ENTITLEMENT_DECISIONS,
                "decision", "ALLOW",
                "reason", EntitlementReasonCodes.ENTITLEMENT_ENABLED
        );

        givenBearer(developerToken(acmeTenantId))
                .header("X-Correlation-Id", "entitlement-obs-corr")
                .body(java.util.Map.of(
                        "productKey", CommercialFixtureSeeder.PRODUCT_KEY,
                        "featureKey", CommercialFixtureSeeder.FEATURE_KEY,
                        "requestedUnits", 1
                ))
                .when()
                .post("/entitlements/check")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW"))
                .header("X-Correlation-Id", equalTo("entitlement-obs-corr"));

        assertThat(counter(
                EntitlementRuntimeMetrics.ENTITLEMENT_DECISIONS,
                "decision", "ALLOW",
                "reason", EntitlementReasonCodes.ENTITLEMENT_ENABLED
        )).isEqualTo(before + 1.0d);

        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("usagecore.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(FORBIDDEN_TAG_KEYS).doesNotContain(tag.getKey());
            }
        }

        String prometheus = RestAssured.given().port(serverPort).basePath("").get("/actuator/prometheus")
                .then().statusCode(200).extract().asString();
        assertThat(prometheus).contains("usagecore_entitlement_decisions_total");
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
