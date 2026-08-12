package io.usagecore.controlplane.domain.catalogue;

/**
 * Commercial snapshot lifecycle. ACTIVATED is immutable; temporal effectiveness is
 * derived separately from {@code effectiveFrom}/{@code effectiveUntil}.
 */
public enum ContractVersionStatus {
    DRAFT,
    ACTIVATED
}
