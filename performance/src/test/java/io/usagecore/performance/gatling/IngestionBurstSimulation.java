package io.usagecore.performance.gatling;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.usagecore.performance.CachedAccessToken;
import io.usagecore.performance.LabJdbc;
import io.usagecore.performance.LoadProfile;
import io.usagecore.performance.PerformanceSettings;
import io.usagecore.performance.verify.PostRunCorrectnessVerifier;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scenario C: short ingest burst, then observe outbox PENDING peak and drain.
 * HTTP success still means durable acceptance only.
 */
public class IngestionBurstSimulation extends Simulation {

    private final AtomicLong peakPending = new AtomicLong();
    private final AtomicBoolean sampling = new AtomicBoolean(true);

    {
        LoadProfile profile = PerformanceSettings.profile() == LoadProfile.SMOKE
                ? LoadProfile.SMOKE
                : LoadProfile.BURST;
        String token = CachedAccessToken.developerToken();
        String product = PerformanceSettings.productKey();
        String meter = PerformanceSettings.eventsMeterKey();
        String runId = PerformanceSettings.runId();

        HttpProtocolBuilder httpProtocol = http
                .baseUrl(PerformanceSettings.usageBaseUrl())
                .acceptHeader("application/json")
                .shareConnections()
                .header("Authorization", "Bearer " + token)
                .userAgentHeader("usagecore-performance-lab");

        ScenarioBuilder scn = scenario("usage-events-burst")
                .exec(http("POST /api/v1/usage/events")
                        .post("/api/v1/usage/events")
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", session -> "perf-burst-" + runId + "-" + session.userId())
                        .body(StringBody(session -> {
                            String key = "perf-burst-" + runId + "-" + UUID.randomUUID();
                            return """
                                    {"productKey":"%s","meterKey":"%s","quantity":1,"occurredAt":"2026-08-18T12:00:00Z","idempotencyKey":"%s"}
                                    """.formatted(product, meter, key).trim();
                        }))
                        .check(status().is(202)));

        setUp(scn.injectOpen(InjectionProfiles.open(profile))).protocols(httpProtocol);
    }

    @Override
    public void before() {
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                try (Connection connection = LabJdbc.open()) {
                    long pending = PostRunCorrectnessVerifier.pendingOutbox(connection);
                    peakPending.accumulateAndGet(pending, Math::max);
                } catch (Exception ignored) {
                    // Sampler is observational; drain verification still runs in after().
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "outbox-pending-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    @Override
    public void after() {
        sampling.set(false);
        System.out.println("burst peakPendingObserved=" + peakPending.get());
        Instant started = Instant.now();
            try (Connection connection = LabJdbc.open()) {
                PostRunCorrectnessVerifier.waitForProcessingComplete(connection);
                PostRunCorrectnessVerifier.verifyIngestion(connection);
            } catch (Exception ex) {
            throw new RuntimeException("Burst drain/correctness check failed", ex);
        }
        System.out.println("burst drainElapsedMs=" + Duration.between(started, Instant.now()).toMillis());
    }
}
