package io.usagecore.controlplane.adapters.inbound.http;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

class ControlPlaneBusinessJourneyTest extends AbstractApiIntegrationTest {

    @Test
    void completeCommercialJourneyFromCatalogueThroughActivationAndTemporalResolution() {
        String tenantId = givenJson()
                .body("""
                        {
                          "tenantKey": "acme",
                          "displayName": "Acme Corp"
                        }
                        """)
                .when()
                .post("/tenants")
                .then()
                .statusCode(201)
                .body("tenantKey", equalTo("acme"))
                .body("displayName", equalTo("Acme Corp"))
                .extract()
                .path("id");

        String productId = givenJson()
                .body("""
                        {
                          "productKey": "datapilot",
                          "name": "DataPilot"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(201)
                .body("productKey", equalTo("datapilot"))
                .extract()
                .path("id");

        String featureId = givenJson()
                .body("""
                        {
                          "featureKey": "scheduled_exports",
                          "name": "Scheduled Exports"
                        }
                        """)
                .when()
                .post("/products/{productId}/features", productId)
                .then()
                .statusCode(201)
                .body("featureKey", equalTo("scheduled_exports"))
                .body("productId", equalTo(productId))
                .extract()
                .path("id");

        String planId = givenJson()
                .body("""
                        {
                          "planKey": "enterprise",
                          "name": "Enterprise"
                        }
                        """)
                .when()
                .post("/products/{productId}/plans", productId)
                .then()
                .statusCode(201)
                .body("status", equalTo("DRAFT"))
                .extract()
                .path("id");

        givenJson()
                .body("""
                        {
                          "mode": "LIMITED",
                          "maxQuantity": 1000000
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, planId, featureId)
                .then()
                .statusCode(200)
                .body("features", hasSize(1))
                .body("features[0].mode", equalTo("LIMITED"))
                .body("features[0].maxQuantity", equalTo(1000000));

        givenJson()
                .when()
                .post("/products/{productId}/plans/{planId}/publish", productId, planId)
                .then()
                .statusCode(200)
                .body("status", equalTo("PUBLISHED"));

        String contractId = givenJson()
                .body("""
                        {
                          "tenantId": "%s",
                          "productId": "%s",
                          "contractKey": "acme-datapilot"
                        }
                        """.formatted(tenantId, productId))
                .when()
                .post("/contracts")
                .then()
                .statusCode(201)
                .body("contractKey", equalTo("acme-datapilot"))
                .extract()
                .path("id");

        int versionNumber = givenJson()
                .body("""
                        {
                          "planId": "%s",
                          "effectiveFrom": "2026-01-01T00:00:00Z",
                          "effectiveUntil": "2026-06-01T00:00:00Z"
                        }
                        """.formatted(planId))
                .when()
                .post("/contracts/{contractId}/versions/from-plan", contractId)
                .then()
                .statusCode(201)
                .body("versionNumber", equalTo(1))
                .body("status", equalTo("DRAFT"))
                .body("sourcePlanId", equalTo(planId))
                .body("entitlements", hasSize(1))
                .body("entitlements[0].maxQuantity", equalTo(1000000))
                .extract()
                .path("versionNumber");

        // Published plan commercial configuration cannot change; snapshot remains independent.
        givenJson()
                .body("""
                        {
                          "mode": "LIMITED",
                          "maxQuantity": 999
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, planId, featureId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));

        // Separate draft plan proves template changes do not rewrite the existing snapshot.
        String draftPlanId = givenJson()
                .body("""
                        {
                          "planKey": "enterprise-draft-alt",
                          "name": "Enterprise Draft Alt"
                        }
                        """)
                .when()
                .post("/products/{productId}/plans", productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        givenJson()
                .body("""
                        {
                          "mode": "LIMITED",
                          "maxQuantity": 42
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, draftPlanId, featureId)
                .then()
                .statusCode(200);

        givenJson()
                .when()
                .get("/contracts/{contractId}/versions/{versionNumber}", contractId, versionNumber)
                .then()
                .statusCode(200)
                .body("entitlements[0].maxQuantity", equalTo(1000000));

        givenJson()
                .body("""
                        {
                          "mode": "LIMITED",
                          "maxQuantity": 2000000
                        }
                        """)
                .when()
                .put(
                        "/contracts/{contractId}/versions/{versionNumber}/entitlements/{featureId}",
                        contractId,
                        versionNumber,
                        featureId
                )
                .then()
                .statusCode(200)
                .body("entitlements[0].maxQuantity", equalTo(2000000));

        givenJson()
                .when()
                .post("/contracts/{contractId}/versions/{versionNumber}/activate", contractId, versionNumber)
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVATED"))
                .body("activatedAt", notNullValue())
                .body("entitlements[0].maxQuantity", equalTo(2000000));

        givenJson()
                .when()
                .get("/contracts/{contractId}/versions/{versionNumber}", contractId, versionNumber)
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVATED"))
                .body("entitlements[0].maxQuantity", equalTo(2000000))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("createdAt")))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("updatedAt")));

        givenJson()
                .queryParam("at", "2026-05-31T23:59:59Z")
                .when()
                .get("/contracts/{contractId}/effective-version", contractId)
                .then()
                .statusCode(200)
                .body("contractId", equalTo(contractId))
                .body("versionNumber", equalTo(1))
                .body("status", equalTo("ACTIVATED"))
                .body("effectiveFrom", equalTo("2026-01-01T00:00:00Z"))
                .body("effectiveUntil", equalTo("2026-06-01T00:00:00Z"))
                .body("activatedAt", notNullValue())
                .body("sourcePlanId", equalTo(planId))
                .body("entitlements[0].maxQuantity", equalTo(2000000));

        givenJson()
                .body("""
                        {
                          "mode": "LIMITED",
                          "maxQuantity": 3
                        }
                        """)
                .when()
                .put(
                        "/contracts/{contractId}/versions/{versionNumber}/entitlements/{featureId}",
                        contractId,
                        versionNumber,
                        featureId
                )
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));
    }
}
