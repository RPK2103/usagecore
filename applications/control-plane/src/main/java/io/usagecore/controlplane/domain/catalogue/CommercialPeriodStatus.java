package io.usagecore.controlplane.domain.catalogue;

/**
 * Commercial lifecycle status for a {@link CommercialPeriod}.
 * Distinct from ContractVersion activation and from UsageWindow event-time buckets.
 */
public enum CommercialPeriodStatus {
    OPEN,
    CLOSING,
    RECONCILING,
    FINALIZED
}
