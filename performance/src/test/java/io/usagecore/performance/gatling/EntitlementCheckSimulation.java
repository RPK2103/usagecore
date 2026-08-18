package io.usagecore.performance.gatling;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.usagecore.performance.CachedAccessToken;
import io.usagecore.performance.LoadProfile;
import io.usagecore.performance.PerformanceSettings;

/**
 * Workload A: authenticated entitlement check. Decision evidence is persisted
 * (append-only entitlement_decision); this is not a cache-only read.
 */
public class EntitlementCheckSimulation extends Simulation {

    {
        LoadProfile profile = PerformanceSettings.profile();
        String token = CachedAccessToken.developerToken();
        String body = """
                {"productKey":"%s","featureKey":"%s","requestedUnits":1}
                """.formatted(PerformanceSettings.productKey(), PerformanceSettings.featureKey()).trim();

        HttpProtocolBuilder httpProtocol = http
                .baseUrl(PerformanceSettings.entitlementBaseUrl())
                .acceptHeader("application/json")
                .shareConnections()
                .header("Authorization", "Bearer " + token)
                .userAgentHeader("usagecore-performance-lab");

        ScenarioBuilder scn = scenario("entitlement-check-" + profile.name().toLowerCase())
                .exec(http("POST /api/v1/entitlements/check")
                        .post("/api/v1/entitlements/check")
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", session -> "perf-entitlement-" + session.userId())
                        .body(StringBody(body))
                        .check(status().is(200)));

        if (profile == LoadProfile.CONTENTION) {
            setUp(scn.injectClosed(InjectionProfiles.closedContention())).protocols(httpProtocol);
        } else {
            setUp(scn.injectOpen(InjectionProfiles.open(profile))).protocols(httpProtocol);
        }
    }
}
