package io.usagecore.usagepipeline.application.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * Half-open event-time window {@code [start, end)}.
 */
public record UsageWindow(Instant start, Instant end) {

    public UsageWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("window start must be before end");
        }
    }

    /**
     * An event is late when processing time is at or after this window's exclusive end.
     */
    public boolean isLate(Instant processedAt) {
        Objects.requireNonNull(processedAt, "processedAt");
        return !processedAt.isBefore(end);
    }

    public boolean contains(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return !occurredAt.isBefore(start) && occurredAt.isBefore(end);
    }
}
