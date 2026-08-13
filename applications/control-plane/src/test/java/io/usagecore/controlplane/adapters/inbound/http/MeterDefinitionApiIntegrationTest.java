package io.usagecore.controlplane.adapters.inbound.http;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.usagecore.controlplane.support.TestJwtSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeterDefinitionApiIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void createAndGetSumCountMaxMeters() {
        String productId = createProduct("datapilot-meters", "DataPilot Meters");
        String featureId = createFeature(productId, "api_access", "API Access");
        String exportFeatureId = createFeature(productId, "scheduled_export", "Scheduled Export");
        String workspaceFeatureId = createFeature(productId, "workspace", "Workspace");

        String sumId = givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "featureId": "%s",
                          "aggregationType": "SUM",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("productId", equalTo(productId))
                .body("featureId", equalTo(featureId))
                .body("meterKey", equalTo("api_requests"))
                .body("displayName", equalTo("API Requests"))
                .body("aggregationType", equalTo("SUM"))
                .body("aggregationWindow", equalTo("MONTHLY"))
                .body("status", equalTo("ACTIVE"))
                .extract()
                .path("id");

        givenJson()
                .body("""
                        {
                          "meterKey": "scheduled_export",
                          "displayName": "Scheduled Export",
                          "featureId": "%s",
                          "aggregationType": "COUNT",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(exportFeatureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .body("aggregationType", equalTo("COUNT"))
                .body("aggregationWindow", equalTo("MONTHLY"))
                .body("featureId", equalTo(exportFeatureId))
                .body("meterKey", equalTo("scheduled_export"));

        givenJson()
                .body("""
                        {
                          "meterKey": "workspace_size",
                          "displayName": "Workspace Size",
                          "featureId": "%s",
                          "aggregationType": "MAX",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(workspaceFeatureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .body("aggregationType", equalTo("MAX"))
                .body("aggregationWindow", equalTo("MONTHLY"));

        givenJson()
                .when()
                .get("/products/{productId}/meters/{meterId}", productId, sumId)
                .then()
                .statusCode(200)
                .body("meterKey", equalTo("api_requests"))
                .body("featureId", equalTo(featureId))
                .body("aggregationType", equalTo("SUM"))
                .body("aggregationWindow", equalTo("MONTHLY"));
    }

    @Test
    void duplicateProductMeterKeyRejected() {
        String productId = createProduct("dup-meter-product", "Dup Meter Product");
        String featureId = createFeature(productId, "api_access", "API Access");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "featureId": "%s",
                          "aggregationType": "SUM",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201);

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests Again",
                          "featureId": "%s",
                          "aggregationType": "COUNT",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("DUPLICATE_RESOURCE"));
    }

    @Test
    void invalidAggregationTypeRejected() {
        String productId = createProduct("bad-agg-product", "Bad Agg Product");
        String featureId = createFeature(productId, "api_access", "API Access");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "featureId": "%s",
                          "aggregationType": "AVERAGE",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void invalidAggregationWindowRejected() {
        String productId = createProduct("bad-window-product", "Bad Window Product");
        String featureId = createFeature(productId, "api_access", "API Access");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "featureId": "%s",
                          "aggregationType": "SUM",
                          "aggregationWindow": "WEEKLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void unknownJsonPropertyRejected() {
        String productId = createProduct("unknown-json-meter", "Unknown JSON Meter");
        String featureId = createFeature(productId, "api_access", "API Access");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "featureId": "%s",
                          "aggregationType": "SUM",
                          "aggregationWindow": "MONTHLY",
                          "pricing": 9.99
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void missingFeatureIdRejected() {
        String productId = createProduct("missing-feature-meter", "Missing Feature Meter");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "aggregationType": "SUM",
                          "aggregationWindow": "MONTHLY"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void nonAdminCannotCreateMeter_auditorCanRead() {
        String productId = createProduct("meter-rbac-product", "Meter RBAC Product");
        String featureId = createFeature(productId, "api_access", "API Access");
        String meterId = givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "featureId": "%s",
                          "aggregationType": "SUM",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        UUID tenantId = UUID.fromString(createTenant("meter-rbac-tenant", "Meter RBAC Tenant"));

        givenBearer(developerToken(tenantId))
                .body("""
                        {
                          "meterKey": "other_meter",
                          "displayName": "Other",
                          "featureId": "%s",
                          "aggregationType": "SUM",
                          "aggregationWindow": "MONTHLY"
                        }
                        """.formatted(featureId))
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));

        givenBearer(TestJwtSupport.auditor(tenantId))
                .when()
                .get("/products/{productId}/meters/{meterId}", productId, meterId)
                .then()
                .statusCode(200)
                .body("meterKey", equalTo("api_requests"))
                .body("featureId", equalTo(featureId))
                .body("aggregationWindow", equalTo("MONTHLY"));
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

    private String createFeature(String productId, String featureKey, String name) {
        return givenJson()
                .body("""
                        {
                          "featureKey": "%s",
                          "name": "%s"
                        }
                        """.formatted(featureKey, name))
                .when()
                .post("/products/{productId}/features", productId)
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
