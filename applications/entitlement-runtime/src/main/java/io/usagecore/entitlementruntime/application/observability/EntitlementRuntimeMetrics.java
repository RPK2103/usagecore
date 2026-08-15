package io.usagecore.entitlementruntime.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Entitlement Runtime custom metrics. Labels are bounded decision/reason codes only.
 */
@Component
public class EntitlementRuntimeMetrics {

    public static final String ENTITLEMENT_DECISIONS = "usagecore.entitlement.decisions";
    public static final String ENTITLEMENT_DECISION_DURATION = "usagecore.entitlement.decision.duration";

    private final MeterRegistry meterRegistry;

    public EntitlementRuntimeMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startDecisionTimer() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void stopDecisionTimer(Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        safe(() -> sample.stop(Timer.builder(ENTITLEMENT_DECISION_DURATION)
                .description("Entitlement evaluation duration")
                .register(meterRegistry)));
    }

    public void recordDecision(String decision, String reason) {
        safe(() -> Counter.builder(ENTITLEMENT_DECISIONS)
                .description("Authenticated entitlement check outcomes")
                .tag("decision", bounded(decision))
                .tag("reason", bounded(reason))
                .register(meterRegistry)
                .increment());
    }

    static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }

    static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Metrics must not roll back commercial transactions.
        }
    }
}
