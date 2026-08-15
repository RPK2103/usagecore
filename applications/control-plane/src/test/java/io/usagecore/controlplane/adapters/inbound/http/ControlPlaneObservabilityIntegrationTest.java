package io.usagecore.controlplane.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.restassured.RestAssured;
import io.usagecore.controlplane.application.observability.ControlPlaneMetrics;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@AutoConfigureObservability
class ControlPlaneObservabilityIntegrationTest extends AbstractApiIntegrationTest {

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

    @Test
    void healthAndPrometheusArePublic_apiStillRequiresJwt() {
        RestAssured.given()
                .port(serverPort)
                .basePath("")
                .when()
                .get("/actuator/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        RestAssured.given()
                .port(serverPort)
                .basePath("")
                .when()
                .get("/actuator/health/liveness")
                .then()
                .statusCode(200);

        RestAssured.given()
                .port(serverPort)
                .basePath("")
                .when()
                .get("/actuator/health/readiness")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        String prometheus = RestAssured.given()
                .port(serverPort)
                .basePath("")
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(prometheus).contains("jvm_memory_used_bytes");
        assertThat(prometheus).contains("hikaricp_connections");

        RestAssured.given()
                .port(serverPort)
                .basePath("")
                .when()
                .get("/actuator/env")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());

        givenUnauthenticatedJson()
                .when()
                .get("/tenants")
                .then()
                .statusCode(401);
    }

    @Test
    void commercialPeriodTransitionIncrementsBoundedMetric() {
        UUID tenantId = UUID.fromString(createTenant("obs-period-acme", "Obs Period"));
        String productId = createProduct("datapilot-obs-period", "DataPilot Obs Period");
        String periodId = givenJson()
                .body("""
                        {
                          "periodStart": "2026-08-01T00:00:00Z",
                          "periodEnd": "2026-09-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods", tenantId, productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        double before = counter(
                ControlPlaneMetrics.COMMERCIAL_PERIOD_TRANSITIONS,
                "from", "OPEN",
                "to", "CLOSING",
                "result", "success"
        );

        givenJson()
                .when()
                .post("/tenants/{tenantId}/products/{productId}/commercial-periods/{periodId}/closing",
                        tenantId, productId, periodId)
                .then()
                .statusCode(200);

        assertThat(counter(
                ControlPlaneMetrics.COMMERCIAL_PERIOD_TRANSITIONS,
                "from", "OPEN",
                "to", "CLOSING",
                "result", "success"
        )).isEqualTo(before + 1.0d);

        assertNoHighCardinalityCustomTags();

        String prometheus = RestAssured.given()
                .port(serverPort)
                .basePath("")
                .get("/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(prometheus).contains("usagecore_commercial_period_transitions_total");
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private void assertNoHighCardinalityCustomTags() {
        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("usagecore.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(FORBIDDEN_TAG_KEYS)
                        .as("custom metric %s must not use high-cardinality tag %s", meter.getId().getName(), tag.getKey())
                        .doesNotContain(tag.getKey());
            }
        }
    }

    private String createProduct(String key, String name) {
        return givenJson()
                .body("""
                        {
                          "productKey": "%s",
                          "name": "%s"
                        }
                        """.formatted(key, name))
                .when()
                .post("/products")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createTenant(String key, String displayName) {
        return givenJson()
                .body("""
                        {
                          "tenantKey": "%s",
                          "displayName": "%s"
                        }
                        """.formatted(key, displayName))
                .when()
                .post("/tenants")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
