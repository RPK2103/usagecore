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

        String sumId = givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "aggregationType": "SUM"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("productId", equalTo(productId))
                .body("meterKey", equalTo("api_requests"))
                .body("displayName", equalTo("API Requests"))
                .body("aggregationType", equalTo("SUM"))
                .body("status", equalTo("ACTIVE"))
                .extract()
                .path("id");

        givenJson()
                .body("""
                        {
                          "meterKey": "scheduled_export",
                          "displayName": "Scheduled Export",
                          "aggregationType": "COUNT"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .body("aggregationType", equalTo("COUNT"))
                .body("meterKey", equalTo("scheduled_export"));

        givenJson()
                .body("""
                        {
                          "meterKey": "workspace_size",
                          "displayName": "Workspace Size",
                          "aggregationType": "MAX"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201)
                .body("aggregationType", equalTo("MAX"));

        givenJson()
                .when()
                .get("/products/{productId}/meters/{meterId}", productId, sumId)
                .then()
                .statusCode(200)
                .body("meterKey", equalTo("api_requests"))
                .body("aggregationType", equalTo("SUM"));
    }

    @Test
    void duplicateProductMeterKeyRejected() {
        String productId = createProduct("dup-meter-product", "Dup Meter Product");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "aggregationType": "SUM"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(201);

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests Again",
                          "aggregationType": "COUNT"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(409)
                .body("errorCode", equalTo("DUPLICATE_RESOURCE"));
    }

    @Test
    void invalidAggregationTypeRejected() {
        String productId = createProduct("bad-agg-product", "Bad Agg Product");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "aggregationType": "AVERAGE"
                        }
                        """)
                .when()
                .post("/products/{productId}/meters", productId)
                .then()
                .statusCode(400)
                .body("errorCode", equalTo("VALIDATION_FAILED"));
    }

    @Test
    void unknownJsonPropertyRejected() {
        String productId = createProduct("unknown-json-meter", "Unknown JSON Meter");

        givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "aggregationType": "SUM",
                          "pricing": 9.99
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
        String meterId = givenJson()
                .body("""
                        {
                          "meterKey": "api_requests",
                          "displayName": "API Requests",
                          "aggregationType": "SUM"
                        }
                        """)
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
                          "aggregationType": "SUM"
                        }
                        """)
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
                .body("meterKey", equalTo("api_requests"));
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
