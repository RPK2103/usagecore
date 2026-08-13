package io.usagecore.controlplane.domain.catalogue;

/**
 * How usage events contribute to a meter aggregate.
 * <ul>
 *   <li>{@link #SUM} — add event quantity</li>
 *   <li>{@link #COUNT} — each event contributes 1 (quantity ignored)</li>
 *   <li>{@link #MAX} — keep the maximum quantity seen</li>
 * </ul>
 */
public enum AggregationType {
    SUM,
    COUNT,
    MAX
}
