package io.usagecore.usagepipeline.adapters.inbound.http;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.usagecore.usagepipeline.support.FixedClockTestConfiguration;
import io.usagecore.usagepipeline.support.RecordingUsageProcessorConfiguration;
import io.usagecore.usagepipeline.support.TestJwtSupport;
import io.usagecore.usagepipeline.support.TestSecurityConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
        TestSecurityConfiguration.class,
        FixedClockTestConfiguration.class,
        RecordingUsageProcessorConfiguration.class
})
abstract class AbstractUsageApiIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
        registry.add("usagecore.kafka.topics.usage-received", () -> "usagecore.usage.received.v1");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-test");
        // Deterministic tests invoke OutboxPublisherApplicationService directly.
        registry.add("usagecore.outbox.publisher.enabled", () -> "false");
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    protected RequestSpecification givenBearer(String token) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .header("Authorization", "Bearer " + token);
    }

    protected RequestSpecification givenUnauthenticatedJson() {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    protected String developerToken(UUID tenantId) {
        return TestJwtSupport.developer(tenantId);
    }
}
