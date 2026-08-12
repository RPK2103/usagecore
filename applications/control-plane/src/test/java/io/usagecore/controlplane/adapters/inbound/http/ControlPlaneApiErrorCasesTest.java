package io.usagecore.controlplane.adapters.inbound.http;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlPlaneApiErrorCasesTest extends AbstractApiIntegrationTest {

    @Test
    void invalidRequestReturns400WithCorrelationId() {
        String correlationId = "11111111-2222-3333-4444-555555555555";

        givenJson()
                .header("X-Correlation-Id", correlationId)
                .body("""
                        {
                          "tenantKey": "",
                          "displayName": "Acme"
                        }
                        """)
                .when()
                .post("/tenants")
                .then()
                .statusCode(400)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("errorCode", equalTo("VALIDATION_FAILED"))
                .body("correlationId", equalTo(correlationId))
                .body("path", equalTo("/api/v1/tenants"))
                .body("timestamp", notNullValue());
    }

    @Test
    void missingResourceReturns404() {
        givenJson()
                .when()
                .get("/tenants/{tenantId}", UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void duplicateTenantKeyReturns409() {
        givenJson()
                .body("""
                        {
                          "tenantKey": "dup-tenant",
                          "displayName": "One"
                        }
                        """)
                .when()
                .post("/tenants")
                .then()
                .statusCode(201);

        givenJson()
                .body("""
                        {
                          "tenantKey": "dup-tenant",
                          "displayName": "Two"
                        }
                        """)
                .when()
                .post("/tenants")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("DUPLICATE_RESOURCE"));
    }

    @Test
    void duplicateProductKeyReturns409() {
        givenJson()
                .body("""
                        {
                          "productKey": "dup-product",
                          "name": "One"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(201);

        givenJson()
                .body("""
                        {
                          "productKey": "dup-product",
                          "name": "Two"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("DUPLICATE_RESOURCE"));
    }

    @Test
    void crossProductFeatureAndPlanMisuseRejected() {
        String productA = createProduct("product-a", "Product A");
        String productB = createProduct("product-b", "Product B");
        String featureB = createFeature(productB, "feature-b", "Feature B");
        String planA = createPlan(productA, "plan-a", "Plan A");

        givenJson()
                .body("""
                        {
                          "mode": "ENABLED"
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productA, planA, featureB)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("DOMAIN_CONFLICT"));

        givenJson()
                .when()
                .get("/products/{productId}/features/{featureId}", productA, featureB)
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("RESOURCE_NOT_FOUND"));

        givenJson()
                .when()
                .get("/products/{productId}/plans/{planId}", productB, planA)
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void invalidLimitedConfigurationRejected() {
        String productId = createProduct("limited-product", "Limited Product");
        String featureId = createFeature(productId, "limited-feature", "Limited Feature");
        String planId = createPlan(productId, "limited-plan", "Limited Plan");

        givenJson()
                .body("""
                        {
                          "mode": "LIMITED"
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, planId, featureId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));

        givenJson()
                .body("""
                        {
                          "mode": "ENABLED",
                          "maxQuantity": 10
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, planId, featureId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void publishedPlanMutationRejected() {
        String productId = createProduct("pub-product", "Pub Product");
        String featureId = createFeature(productId, "pub-feature", "Pub Feature");
        String planId = createPlan(productId, "pub-plan", "Pub Plan");

        givenJson()
                .body("""
                        {
                          "mode": "ENABLED"
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, planId, featureId)
                .then()
                .statusCode(200);

        givenJson()
                .when()
                .post("/products/{productId}/plans/{planId}/publish", productId, planId)
                .then()
                .statusCode(200);

        givenJson()
                .body("""
                        {
                          "mode": "DISABLED"
                        }
                        """)
                .when()
                .put("/products/{productId}/plans/{planId}/features/{featureId}", productId, planId, featureId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    void activatedVersionMutationAndOverlapRejected() {
        String tenantId = createTenant("overlap-tenant", "Overlap Tenant");
        String productId = createProduct("overlap-product", "Overlap Product");
        String featureId = createFeature(productId, "overlap-feature", "Overlap Feature");
        String contractId = createContract(tenantId, productId, "overlap-contract");

        int v1 = createEmptyDraft(contractId, "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z");
        givenJson()
                .body("""
                        {
                          "mode": "ENABLED"
                        }
                        """)
                .when()
                .put(
                        "/contracts/{contractId}/versions/{versionNumber}/entitlements/{featureId}",
                        contractId,
                        v1,
                        featureId
                )
                .then()
                .statusCode(200);
        givenJson()
                .when()
                .post("/contracts/{contractId}/versions/{versionNumber}/activate", contractId, v1)
                .then()
                .statusCode(200);

        givenJson()
                .body("""
                        {
                          "mode": "DISABLED"
                        }
                        """)
                .when()
                .put(
                        "/contracts/{contractId}/versions/{versionNumber}/entitlements/{featureId}",
                        contractId,
                        v1,
                        featureId
                )
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("INVALID_STATE_TRANSITION"));

        int v2 = createEmptyDraft(contractId, "2026-03-01T00:00:00Z", "2026-12-01T00:00:00Z");
        givenJson()
                .when()
                .post("/contracts/{contractId}/versions/{versionNumber}/activate", contractId, v2)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("COMMERCIAL_INTERVAL_CONFLICT"));
    }

    @Test
    void effectiveVersionHalfOpenBoundaryAndMissingInstant() {
        String tenantId = createTenant("boundary-api-tenant", "Boundary API Tenant");
        String productId = createProduct("boundary-api-product", "Boundary API Product");
        String contractId = createContract(tenantId, productId, "boundary-api-contract");

        int v1 = createEmptyDraft(contractId, "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z");
        givenJson()
                .when()
                .post("/contracts/{contractId}/versions/{versionNumber}/activate", contractId, v1)
                .then()
                .statusCode(200);

        int v2 = createEmptyDraft(contractId, "2026-06-01T00:00:00Z", null);
        givenJson()
                .when()
                .post("/contracts/{contractId}/versions/{versionNumber}/activate", contractId, v2)
                .then()
                .statusCode(200);

        givenJson()
                .queryParam("at", "2026-05-31T23:59:59Z")
                .when()
                .get("/contracts/{contractId}/effective-version", contractId)
                .then()
                .statusCode(200)
                .body("versionNumber", equalTo(1));

        givenJson()
                .queryParam("at", "2026-06-01T00:00:00Z")
                .when()
                .get("/contracts/{contractId}/effective-version", contractId)
                .then()
                .statusCode(200)
                .body("versionNumber", equalTo(2));

        givenJson()
                .queryParam("at", "2025-12-31T23:59:59Z")
                .when()
                .get("/contracts/{contractId}/effective-version", contractId)
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unknownVersionNumberOnCreateContractVersionReturns400() {
        String tenantId = createTenant("no-version-tenant", "No Version Tenant");
        String productId = createProduct("no-version-product", "No Version Product");
        String contractId = createContract(tenantId, productId, "no-version-contract");
        String correlationId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

        givenJson()
                .header("X-Correlation-Id", correlationId)
                .body("""
                        {
                          "versionNumber": 47,
                          "effectiveFrom": "2026-01-01T00:00:00Z",
                          "effectiveUntil": "2026-06-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/contracts/{contractId}/versions", contractId)
                .then()
                .statusCode(400)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("errorCode", equalTo("VALIDATION_FAILED"))
                .body("correlationId", equalTo(correlationId))
                .body("message", equalTo("Request validation failed"));

        givenJson()
                .when()
                .get("/contracts/{contractId}/versions/{versionNumber}", contractId, 1)
                .then()
                .statusCode(404)
                .body("errorCode", equalTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unknownPropertyOnCreateContractVersionReturns400() {
        String tenantId = createTenant("unknown-prop-tenant", "Unknown Prop Tenant");
        String productId = createProduct("unknown-prop-product", "Unknown Prop Product");
        String contractId = createContract(tenantId, productId, "unknown-prop-contract");
        String correlationId = "ffffffff-1111-2222-3333-444444444444";

        givenJson()
                .header("X-Correlation-Id", correlationId)
                .body("""
                        {
                          "effectiveFrom": "2026-01-01T00:00:00Z",
                          "effectiveUntil": "2026-06-01T00:00:00Z",
                          "unexpectedField": "nope"
                        }
                        """)
                .when()
                .post("/contracts/{contractId}/versions", contractId)
                .then()
                .statusCode(400)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("errorCode", equalTo("VALIDATION_FAILED"))
                .body("correlationId", equalTo(correlationId))
                .body("message", equalTo("Request validation failed"));
    }

    @Test
    void validCreateContractVersionRequestStillWorks() {
        String tenantId = createTenant("valid-version-tenant", "Valid Version Tenant");
        String productId = createProduct("valid-version-product", "Valid Version Product");
        String contractId = createContract(tenantId, productId, "valid-version-contract");

        givenJson()
                .body("""
                        {
                          "effectiveFrom": "2026-01-01T00:00:00Z",
                          "effectiveUntil": "2026-06-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/contracts/{contractId}/versions", contractId)
                .then()
                .statusCode(201)
                .body("versionNumber", equalTo(1))
                .body("status", equalTo("DRAFT"));
    }

    private String createTenant(String key, String name) {
        return givenJson()
                .body(Map.of("tenantKey", key, "displayName", name))
                .when()
                .post("/tenants")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createProduct(String key, String name) {
        return givenJson()
                .body(Map.of("productKey", key, "name", name))
                .when()
                .post("/products")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createFeature(String productId, String key, String name) {
        return givenJson()
                .body(Map.of("featureKey", key, "name", name))
                .when()
                .post("/products/{productId}/features", productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createPlan(String productId, String key, String name) {
        return givenJson()
                .body(Map.of("planKey", key, "name", name))
                .when()
                .post("/products/{productId}/plans", productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createContract(String tenantId, String productId, String contractKey) {
        return givenJson()
                .body("""
                        {
                          "tenantId": "%s",
                          "productId": "%s",
                          "contractKey": "%s"
                        }
                        """.formatted(tenantId, productId, contractKey))
                .when()
                .post("/contracts")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private int createEmptyDraft(String contractId, String from, String until) {
        String body = until == null
                ? """
                        {
                          "effectiveFrom": "%s"
                        }
                        """.formatted(from)
                : """
                        {
                          "effectiveFrom": "%s",
                          "effectiveUntil": "%s"
                        }
                        """.formatted(from, until);
        return givenJson()
                .body(body)
                .when()
                .post("/contracts/{contractId}/versions", contractId)
                .then()
                .statusCode(201)
                .extract()
                .path("versionNumber");
    }
}
