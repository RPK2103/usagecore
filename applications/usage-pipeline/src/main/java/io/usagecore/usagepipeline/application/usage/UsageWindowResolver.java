package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code occurredAt + aggregationWindow → [start, end)} in UTC.
 * No database access — pure temporal mapping.
 */
@Component
public class UsageWindowResolver {

    public UsageWindow resolve(Instant occurredAt, AggregationWindow aggregationWindow) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregationWindow, "aggregationWindow");
        return switch (aggregationWindow) {
            case DAILY -> resolveDaily(occurredAt);
            case MONTHLY -> resolveMonthly(occurredAt);
        };
    }

    private static UsageWindow resolveDaily(Instant occurredAt) {
        LocalDate day = LocalDate.ofInstant(occurredAt, ZoneOffset.UTC);
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new UsageWindow(start, end);
    }

    private static UsageWindow resolveMonthly(Instant occurredAt) {
        YearMonth month = YearMonth.from(occurredAt.atZone(ZoneOffset.UTC));
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new UsageWindow(start, end);
    }
}
