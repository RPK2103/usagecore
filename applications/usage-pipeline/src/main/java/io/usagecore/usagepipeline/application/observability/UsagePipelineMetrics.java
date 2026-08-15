package io.usagecore.usagepipeline.application.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Usage Pipeline custom metrics. Labels are bounded enums only — never tenant/event/request ids.
 * Instrumentation must not become correctness authority and must not roll back transactions.
 */
@Component
public class UsagePipelineMetrics {

    public static final String OUTBOX_PUBLISH = "usagecore.outbox.publish";
    public static final String OUTBOX_PUBLISH_DURATION = "usagecore.outbox.publish.duration";
    public static final String OUTBOX_PENDING = "usagecore.outbox.pending";
    public static final String OUTBOX_OLDEST_PENDING_AGE = "usagecore.outbox.oldest.pending.age";
    public static final String USAGE_EVENTS_PROCESSED = "usagecore.usage.events.processed";
    public static final String USAGE_PROCESS_DURATION = "usagecore.usage.process.duration";
    public static final String USAGE_DLQ = "usagecore.usage.dlq";
    public static final String AGGREGATE_UPDATES = "usagecore.aggregate.updates";
    public static final String QUOTA_DECISIONS = "usagecore.quota.decisions";
    public static final String QUOTA_CONSUME_DURATION = "usagecore.quota.consume.duration";
    public static final String COMMERCIAL_USAGE_EXCEPTIONS = "usagecore.commercial.usage.exceptions";
    public static final String COMMERCIAL_USAGE_EXCEPTIONS_UNRESOLVED =
            "usagecore.commercial.usage.exceptions.unresolved";
    public static final String RECONCILIATION_RUNS = "usagecore.reconciliation.runs";
    public static final String RECONCILIATION_DURATION = "usagecore.reconciliation.duration";
    public static final String RECONCILIATION_MISMATCHES = "usagecore.reconciliation.mismatches";
    public static final String USAGE_ADJUSTMENTS = "usagecore.usage.adjustments";
    public static final String USAGE_ADJUSTMENT_DURATION = "usagecore.usage.adjustment.duration";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";
    public static final String RESULT_APPLIED = "applied";
    public static final String RESULT_DUPLICATE = "duplicate";
    public static final String RESULT_QUARANTINED = "quarantined";
    public static final String RESULT_REJECTED_INVALID = "rejected_invalid";
    public static final String RESULT_REPLAY = "replay";
    public static final String RESULT_CONFLICT = "conflict";
    public static final String RESULT_REJECTED = "rejected";
    public static final String RESULT_MATCH = "match";
    public static final String RESULT_MISMATCH = "mismatch";
    public static final String RESULT_FAILED = "failed";

    private final MeterRegistry meterRegistry;

    public UsagePipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startTimer() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void stopTimer(Timer.Sample sample, String timerName) {
        if (sample == null) {
            return;
        }
        safe(() -> sample.stop(Timer.builder(timerName).register(meterRegistry)));
    }

    public void recordOutboxPublish(String result) {
        increment(OUTBOX_PUBLISH, "Outbox Kafka publish attempts", "result", result);
    }

    public void recordUsageProcessed(String result) {
        increment(USAGE_EVENTS_PROCESSED, "UsageReceived consumer processing outcomes", "result", result);
    }

    public void recordDlq(String reason) {
        increment(USAGE_DLQ, "UsageReceived records recovered to DLQ", "reason", reason);
    }

    public void recordAggregateUpdate(String aggregationType) {
        increment(AGGREGATE_UPDATES, "Derived aggregate mutations", "aggregationType", aggregationType);
    }

    public void recordQuotaDecision(String decision, String reason) {
        safe(() -> Counter.builder(QUOTA_DECISIONS)
                .description("Synchronous quota admission decisions")
                .tag("decision", bounded(decision))
                .tag("reason", bounded(reason))
                .register(meterRegistry)
                .increment());
    }

    public void recordCommercialException(String reason) {
        increment(COMMERCIAL_USAGE_EXCEPTIONS, "Commercial usage quarantine records", "reason", reason);
    }

    public void recordReconciliationRun(String result) {
        increment(RECONCILIATION_RUNS, "Reconciliation run completions", "result", result);
    }

    public void recordReconciliationMismatch(String type) {
        increment(RECONCILIATION_MISMATCHES, "Reconciliation item mismatch classifications", "type", type);
    }

    public void recordAdjustment(String result) {
        increment(USAGE_ADJUSTMENTS, "Explicit UsageAdjustment outcomes", "result", result);
    }

    private void increment(String name, String description, String tagKey, String tagValue) {
        safe(() -> Counter.builder(name)
                .description(description)
                .tag(tagKey, bounded(tagValue))
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
