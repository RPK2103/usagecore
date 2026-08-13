package io.usagecore.usagepipeline.adapters.inbound.messaging;

import io.restassured.RestAssured;
import io.usagecore.usagepipeline.support.FixedClockTestConfiguration;
import io.usagecore.usagepipeline.support.TestSecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration base that uses the real {@code IdempotentUsageReceivedProcessor}
 * (no recording stub).
 * <p>
 * Subclasses that start a Kafka listener must set a unique
 * {@code usagecore.kafka.consumer-group} so cached Spring contexts cannot steal
 * partitions from each other on the shared Testcontainers broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestSecurityConfiguration.class, FixedClockTestConfiguration.class})
abstract class AbstractIdempotentConsumerIntegrationTest {

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
        registry.add("usagecore.kafka.topics.usage-received-dlq", () -> "usagecore.usage.received.v1.dlq");
        registry.add("usagecore.outbox.publisher.enabled", () -> "false");
        registry.add("usagecore.kafka.consumer-retry.interval-ms", () -> "50");
        registry.add("usagecore.kafka.consumer-retry.max-attempts", () -> "2");
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }
}
