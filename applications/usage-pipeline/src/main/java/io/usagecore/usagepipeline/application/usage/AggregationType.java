package io.usagecore.usagepipeline.application.usage;

/**
 * Aggregation strategy from {@code meter_definition}. Aggregation type is never taken from the event.
 */
public enum AggregationType {
    SUM,
    COUNT,
    MAX
}
