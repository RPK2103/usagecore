package io.usagecore.controlplane.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.usagecore.controlplane.support.TestJwtSupport;
import io.usagecore.controlplane.support.TestSecurityConfiguration;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Dedicated PostgreSQL container so pause cannot stall Phase 1–9 Control Plane tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ControlPlanePostgresOutageIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ControlPlanePostgresOutageIntegrationTest::jdbcUrlWithTimeouts);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
    }

    @BeforeEach
    void restAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
        RestAssured.config = RestAssuredConfig.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 5000)
                        .setParam("http.socket.timeout", 10000)
        );
    }

    @AfterEach
    void unpause() {
        TestcontainersPause.unpause(POSTGRES);
    }

    @Test
    void postgresUnavailable_mutatingApiFails_readinessDown_thenRecovers() {
        RestAssured.given().port(port).basePath("").get("/actuator/health/readiness")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        TestcontainersPause.pause(POSTGRES);

        RestAssured.given().port(port).basePath("").get("/actuator/health/readiness")
                .then()
                .statusCode(anyOf(equalTo(503), equalTo(500)))
                .body("status", not(equalTo("UP")));

        int status = RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + TestJwtSupport.platformAdmin())
                .body(Map.of(
                        "tenantKey", "resilience-" + UUID.randomUUID(),
                        "displayName", "Resilience Tenant"
                ))
                .when()
                .post("/tenants")
                .then()
                .statusCode(anyOf(equalTo(503), equalTo(500)))
                .extract()
                .statusCode();
        assertThat(status).isNotEqualTo(201);

        TestcontainersPause.unpause(POSTGRES);

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            RestAssured.given().port(port).basePath("").get("/actuator/health/readiness")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));
            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .accept(ContentType.JSON)
                    .header("Authorization", "Bearer " + TestJwtSupport.platformAdmin())
                    .body(Map.of(
                            "tenantKey", "recovered-" + UUID.randomUUID(),
                            "displayName", "Recovered Tenant"
                    ))
                    .when()
                    .post("/tenants")
                    .then()
                    .statusCode(201);
        });
    }

    private static String jdbcUrlWithTimeouts() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "connectTimeout=2&socketTimeout=2&loginTimeout=2";
    }
}
