package io.usagecore.usagepipeline.adapters.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.usagecore.usagepipeline.application.commercial.CommercialUsageExceptionRepository;
import io.usagecore.usagepipeline.application.observability.UsagePipelineMetrics;
import io.usagecore.usagepipeline.application.outbox.OutboxEventRepository;
import io.usagecore.usagepipeline.application.outbox.OutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Scrape-time gauges. Queries run when Prometheus scrapes, not on every business operation.
 */
@Component
public class UsagePipelineGaugeBinder implements MeterBinder {

    private final OutboxEventRepository outboxEventRepository;
    private final CommercialUsageExceptionRepository commercialUsageExceptionRepository;
    private final Clock clock;

    public UsagePipelineGaugeBinder(
            OutboxEventRepository outboxEventRepository,
            CommercialUsageExceptionRepository commercialUsageExceptionRepository,
            Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.commercialUsageExceptionRepository = commercialUsageExceptionRepository;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(UsagePipelineMetrics.OUTBOX_PENDING, this, UsagePipelineGaugeBinder::pendingOutboxCount)
                .description("Current PENDING outbox rows")
                .register(registry);
        Gauge.builder(
                        UsagePipelineMetrics.OUTBOX_OLDEST_PENDING_AGE,
                        this,
                        UsagePipelineGaugeBinder::oldestPendingAgeSeconds
                )
                .description("Age in seconds of the oldest PENDING outbox row")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(
                        UsagePipelineMetrics.COMMERCIAL_USAGE_EXCEPTIONS_UNRESOLVED,
                        this,
                        UsagePipelineGaugeBinder::unresolvedExceptionCount
                )
                .description("Commercial usage exceptions without an applied UsageAdjustment")
                .register(registry);
    }

    private double pendingOutboxCount() {
        try {
            return outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            Optional<Instant> oldest = outboxEventRepository.oldestPendingCreatedAt();
            if (oldest.isEmpty()) {
                return 0.0d;
            }
            long seconds = Duration.between(oldest.get(), clock.instant()).getSeconds();
            return Math.max(0.0d, seconds);
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    private double unresolvedExceptionCount() {
        try {
            return commercialUsageExceptionRepository.countUnresolved();
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }
}
