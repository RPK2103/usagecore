package io.usagecore.controlplane.adapters.inbound.http;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.usagecore.controlplane.support.TestJwtSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ControlPlaneSecurityIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void missingTokenReturns401ApiError() {
        String correlationId = "bbbbbbbb-bbbb-cccc-dddd-222222222222";
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        givenUnauthenticatedJson()
                .header("X-Correlation-Id", correlationId)
                .when()
                .get("/tenants/{tenantId}", tenantId)
                .then()
                .statusCode(401)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("errorCode", equalTo("UNAUTHORIZED"))
                .body("correlationId", equalTo(correlationId))
                .body("path", equalTo("/api/v1/tenants/" + tenantId))
                .body("timestamp", notNullValue());
    }

    @Test
    void malformedTokenReturns401() {
        givenUnauthenticatedJson()
                .header("Authorization", "Bearer not-a-valid-jwt")
                .when()
                .get("/products/{productId}", UUID.randomUUID())
                .then()
                .statusCode(401)
                .body("errorCode", equalTo("UNAUTHORIZED"));
    }

    @Test
    void platformAdminCanCreateGlobalCatalogueResources() {
        givenBearer(platformAdminToken())
                .body("""
                        {
                          "tenantKey": "sec-platform-tenant",
                          "displayName": "Security Platform Tenant"
                        }
                        """)
                .when()
                .post("/tenants")
                .then()
                .statusCode(201);

        givenBearer(platformAdminToken())
                .body("""
                        {
                          "productKey": "sec-platform-product",
                          "name": "Security Platform Product"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(201);
    }

    @Test
    void tenantIdentityCannotCreateGlobalProductOrPlan() {
        UUID acmeTenantId = UUID.fromString(createTenant("sec-acme-deny-cat", "Acme Deny Cat"));
        String productId = createProduct("sec-product-for-deny", "Product For Deny");

        givenBearer(contractManagerToken(acmeTenantId))
                .body("""
                        {
                          "productKey": "tenant-cannot-create",
                          "name": "Nope"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));

        givenBearer(tenantAdminToken(acmeTenantId))
                .body("""
                        {
                          "planKey": "tenant-cannot-plan",
                          "name": "Nope"
                        }
                        """)
                .when()
                .post("/products/{productId}/plans", productId)
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));
    }

    @Test
    void contractManagerCanAccessOwnTenantContractAndPlatformAdminCanAccessBoth() {
        String acmeTenantId = createTenant("sec-acme", "Acme");
        String globexTenantId = createTenant("sec-globex", "Globex");
        String productId = createProduct("sec-shared-product", "Shared Product");

        String acmeContractId = createContract(acmeTenantId, productId, "acme-contract");
        String globexContractId = createContract(globexTenantId, productId, "globex-contract");

        UUID acmeId = UUID.fromString(acmeTenantId);
        UUID globexId = UUID.fromString(globexTenantId);

        givenBearer(contractManagerToken(acmeId))
                .when()
                .get("/contracts/{contractId}", acmeContractId)
                .then()
                .statusCode(200)
                .body("contractKey", equalTo("acme-contract"));

        givenBearer(platformAdminToken())
                .when()
                .get("/contracts/{contractId}", acmeContractId)
                .then()
                .statusCode(200);

        givenBearer(platformAdminToken())
                .when()
                .get("/contracts/{contractId}", globexContractId)
                .then()
                .statusCode(200)
                .body("contractKey", equalTo("globex-contract"));
    }

    @Test
    void crossTenantContractAccessDeniedAndAudited() {
        String correlationId = "cccccccc-dddd-eeee-ffff-333333333333";
        String acmeTenantId = createTenant("sec-acme-x", "Acme X");
        String globexTenantId = createTenant("sec-globex-x", "Globex X");
        String productId = createProduct("sec-x-product", "X Product");
        String globexContractId = createContract(globexTenantId, productId, "globex-x-contract");

        UUID acmeId = UUID.fromString(acmeTenantId);
        long before = countAuditEvents("CROSS_TENANT_ACCESS_DENIED");

        givenBearer(contractManagerToken(acmeId))
                .header("X-Correlation-Id", correlationId)
                .when()
                .get("/contracts/{contractId}", globexContractId)
                .then()
                .statusCode(403)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("errorCode", equalTo("FORBIDDEN"))
                .body("correlationId", equalTo(correlationId));

        long after = countAuditEvents("CROSS_TENANT_ACCESS_DENIED");
        org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before + 1);

        Integer matching = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM security_audit_event
                WHERE event_type = 'CROSS_TENANT_ACCESS_DENIED'
                  AND correlation_id = ?
                  AND resource_id = ?
                """,
                Integer.class,
                correlationId,
                globexContractId
        );
        org.assertj.core.api.Assertions.assertThat(matching).isEqualTo(1);
    }

    @Test
    void tenantAdminCannotMutateContractConfiguration() {
        String acmeTenantId = createTenant("sec-acme-ta", "Acme TA");
        String productId = createProduct("sec-ta-product", "TA Product");
        String contractId = createContract(acmeTenantId, productId, "acme-ta-contract");
        UUID acmeId = UUID.fromString(acmeTenantId);

        givenBearer(tenantAdminToken(acmeId))
                .when()
                .get("/contracts/{contractId}", contractId)
                .then()
                .statusCode(200);

        givenBearer(tenantAdminToken(acmeId))
                .body("""
                        {
                          "effectiveFrom": "2026-01-01T00:00:00Z",
                          "effectiveUntil": "2026-06-01T00:00:00Z"
                        }
                        """)
                .when()
                .post("/contracts/{contractId}/versions", contractId)
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));
    }

    @Test
    void developerCannotUseAdministrativeMutationEndpoints() {
        UUID tenantId = UUID.fromString(createTenant("sec-dev-tenant", "Dev Tenant"));

        givenBearer(developerToken(tenantId))
                .body("""
                        {
                          "tenantKey": "dev-cannot",
                          "displayName": "Nope"
                        }
                        """)
                .when()
                .post("/tenants")
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));

        givenBearer(developerToken(tenantId))
                .body("""
                        {
                          "productKey": "dev-cannot-product",
                          "name": "Nope"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(403)
                .body("errorCode", equalTo("FORBIDDEN"));
    }

    @Test
    void correlationIdSurvivesSecurityErrorHandling() {
        String correlationId = "dddddddd-eeee-ffff-aaaa-444444444444";
        UUID tenantId = UUID.fromString(createTenant("sec-corr-tenant", "Corr Tenant"));

        givenBearer(TestJwtSupport.billingOperator(tenantId))
                .header("X-Correlation-Id", correlationId)
                .body("""
                        {
                          "productKey": "billing-cannot",
                          "name": "Nope"
                        }
                        """)
                .when()
                .post("/products")
                .then()
                .statusCode(403)
                .header("X-Correlation-Id", equalTo(correlationId))
                .body("correlationId", equalTo(correlationId))
                .body("errorCode", equalTo("FORBIDDEN"));
    }

    private long countAuditEvents(String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security_audit_event WHERE event_type = ?",
                Long.class,
                eventType
        );
        return count == null ? 0L : count;
    }

    private String createTenant(String key, String name) {
        return givenBearer(platformAdminToken())
                .body(Map.of("tenantKey", key, "displayName", name))
                .when()
                .post("/tenants")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createProduct(String key, String name) {
        return givenBearer(platformAdminToken())
                .body(Map.of("productKey", key, "name", name))
                .when()
                .post("/products")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createContract(String tenantId, String productId, String contractKey) {
        return givenBearer(platformAdminToken())
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
}
