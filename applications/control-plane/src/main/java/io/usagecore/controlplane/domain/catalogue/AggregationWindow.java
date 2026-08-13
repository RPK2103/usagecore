package io.usagecore.controlplane.domain.catalogue;

/**
 * Calendar-based event-time aggregation window (UTC, half-open).
 * <ul>
 *   <li>{@link #DAILY} — [start of UTC day, start of next UTC day)</li>
 *   <li>{@link #MONTHLY} — [start of UTC month, start of next UTC month)</li>
 * </ul>
 */
public enum AggregationWindow {
    DAILY,
    MONTHLY
}
