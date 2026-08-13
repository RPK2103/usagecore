package io.usagecore.usagepipeline.application.commercial;

/**
 * Commercial period lifecycle status mirrored from shared PostgreSQL schema.
 * No Control Plane compile-time dependency.
 */
public enum CommercialPeriodStatus {
    OPEN,
    CLOSING,
    RECONCILING,
    FINALIZED
}
