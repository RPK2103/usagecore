package io.usagecore.usagepipeline.application.usage;

/**
 * Calendar-based event-time aggregation window from {@code meter_definition}.
 * Window assignment uses UTC and half-open intervals {@code [start, end)}.
 */
public enum AggregationWindow {
    DAILY,
    MONTHLY
}
