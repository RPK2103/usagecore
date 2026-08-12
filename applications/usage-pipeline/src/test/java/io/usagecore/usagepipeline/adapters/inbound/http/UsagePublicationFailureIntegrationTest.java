package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.hamcrest.Matchers.equalTo;

import io.usagecore.usagepipeline.application.usage.UsageEventPublisher;
import io.usagecore.usagepipeline.application.usage.UsagePublicationException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Proves publisher failure is mapped to HTTP 503 — never a false ACCEPTED/202.
 */
@Import(UsagePublicationFailureIntegrationTest.FailingPublisherConfig.class)
class UsagePublicationFailureIntegrationTest extends AbstractUsageApiIntegrationTest {

    @TestConfiguration
    static class FailingPublisherConfig {
        @Bean
        @Primary
        UsageEventPublisher failingUsageEventPublisher() {
            return (event, partitionKey) -> {
                throw new UsagePublicationException("simulated broker unavailable");
            };
        }
    }

    @Test
    void producerFailure_returns503Not202() {
        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Map<String, Object> body = new HashMap<>();
        body.put("productKey", "datapilot-cloud");
        body.put("meterKey", "scheduled_export");
        body.put("quantity", 1);
        body.put("occurredAt", "2026-08-12T14:30:00Z");
        body.put("idempotencyKey", "export-job-fail");

        givenBearer(developerToken(tenantId))
                .body(body)
                .when()
                .post("/usage/events")
                .then()
                .statusCode(503)
                .body("errorCode", equalTo("SERVICE_UNAVAILABLE"));
    }
}
