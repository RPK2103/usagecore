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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
        TestSecurityConfiguration.class,
        FixedClockTestConfiguration.class,
        RecordingUsageProcessorConfiguration.class
})
@Testcontainers
abstract class AbstractUsageApiIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
        registry.add("usagecore.kafka.topics.usage-received", () -> "usagecore.usage.received.v1");
        registry.add("usagecore.kafka.consumer-group", () -> "usagecore-usage-pipeline-v1-test");
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
