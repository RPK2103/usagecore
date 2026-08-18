package io.usagecore.performance.gatling;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.usagecore.performance.CachedAccessToken;
import io.usagecore.performance.LoadProfile;
import io.usagecore.performance.PerformanceSettings;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Workload C: synchronous {@code POST /api/v1/usage/consume}.
 * HTTP 200 + decision=REJECTED is a business rejection, not a technical failure.
 */
public class UsageConsumeSimulation extends Simulation {

    static final AtomicLong ACCEPTED = new AtomicLong();
    static final AtomicLong BUSINESS_REJECTED = new AtomicLong();

    {
        LoadProfile profile = PerformanceSettings.profile();
        String token = CachedAccessToken.developerToken();
        String product = PerformanceSettings.productKey();
        String meter = PerformanceSettings.consumeMeterKey();
        String runId = PerformanceSettings.runId();

        HttpProtocolBuilder httpProtocol = http
                .baseUrl(PerformanceSettings.usageBaseUrl())
                .acceptHeader("application/json")
                .shareConnections()
                .header("Authorization", "Bearer " + token)
                .userAgentHeader("usagecore-performance-lab");

        ScenarioBuilder scn = scenario("usage-consume-" + profile.name().toLowerCase())
                .exec(http("POST /api/v1/usage/consume")
                        .post("/api/v1/usage/consume")
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", session -> "perf-consume-" + runId + "-" + session.userId())
                        .body(StringBody(session -> {
                            String key = "perf-consume-" + runId + "-" + UUID.randomUUID();
                            return """
                                    {"productKey":"%s","meterKey":"%s","quantity":1,"occurredAt":"2026-08-18T12:00:00Z","idempotencyKey":"%s"}
                                    """.formatted(product, meter, key).trim();
                        }))
                        .check(status().is(200))
                        .check(jsonPath("$.decision").saveAs("decision")))
                .exec(session -> {
                    String decision = session.getString("decision");
                    if ("REJECTED".equals(decision)) {
                        BUSINESS_REJECTED.incrementAndGet();
                    } else if ("ACCEPTED".equals(decision)) {
                        ACCEPTED.incrementAndGet();
                    }
                    return session;
                });

        if (profile == LoadProfile.CONTENTION) {
            setUp(scn.injectClosed(InjectionProfiles.closedContention())).protocols(httpProtocol);
        } else {
            setUp(scn.injectOpen(InjectionProfiles.open(profile))).protocols(httpProtocol);
        }
    }

    @Override
    public void after() {
        System.out.println("usage-consume businessAccepted=" + ACCEPTED.get());
        System.out.println("usage-consume businessRejected=" + BUSINESS_REJECTED.get());
    }
}
