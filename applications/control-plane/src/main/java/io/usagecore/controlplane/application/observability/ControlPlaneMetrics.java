package io.usagecore.controlplane.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Control Plane custom metrics. Labels are bounded enums/status values only.
 * Observability failures must never change commercial outcomes.
 */
@Component
public class ControlPlaneMetrics {

    public static final String COMMERCIAL_PERIOD_TRANSITIONS = "usagecore.commercial.period.transitions";

    private final MeterRegistry meterRegistry;

    public ControlPlaneMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordPeriodTransition(String from, String to, String result) {
        safe(() -> Counter.builder(COMMERCIAL_PERIOD_TRANSITIONS)
                .description("Commercial period lifecycle transitions")
                .tag("from", bounded(from))
                .tag("to", bounded(to))
                .tag("result", bounded(result))
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
