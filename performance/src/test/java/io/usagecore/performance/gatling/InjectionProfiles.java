package io.usagecore.performance.gatling;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;

import io.gatling.javaapi.core.ClosedInjectionStep;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.usagecore.performance.LoadProfile;
import io.usagecore.performance.PerformanceSettings;
import java.time.Duration;

final class InjectionProfiles {

    private InjectionProfiles() {
    }

    static OpenInjectionStep[] open(LoadProfile profile) {
        return switch (profile) {
            case SMOKE -> new OpenInjectionStep[] {atOnceUsers(1)};
            case WARMUP -> new OpenInjectionStep[] {
                    constantUsersPerSec(2).during(Duration.ofSeconds(Math.min(10, PerformanceSettings.durationSeconds())))
            };
            case BASELINE -> new OpenInjectionStep[] {
                    constantUsersPerSec(PerformanceSettings.requestsPerSecond())
                            .during(PerformanceSettings.duration())
            };
            case RAMP -> new OpenInjectionStep[] {
                    rampUsersPerSec(1)
                            .to(Math.max(PerformanceSettings.requestsPerSecond() * 3, 8))
                            .during(PerformanceSettings.ramp())
            };
            case SUSTAINED -> new OpenInjectionStep[] {
                    constantUsersPerSec(PerformanceSettings.requestsPerSecond())
                            .during(Duration.ofSeconds(Math.max(PerformanceSettings.durationSeconds(), 45)))
            };
            case BURST -> new OpenInjectionStep[] {
                    constantUsersPerSec(PerformanceSettings.burstRps())
                            .during(Duration.ofSeconds(PerformanceSettings.burstSeconds()))
            };
            case CONTENTION -> throw new IllegalArgumentException("CONTENTION is a closed-model profile");
        };
    }

    static ClosedInjectionStep[] closedContention() {
        return new ClosedInjectionStep[] {
                constantConcurrentUsers(Math.max(PerformanceSettings.users(), 8))
                        .during(PerformanceSettings.duration())
        };
    }
}
