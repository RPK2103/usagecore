package io.usagecore.entitlementruntime.domain;

/**
 * Raised when commercial temporal invariants are corrupted (e.g. multiple effective versions).
 */
public class CommercialInvariantException extends RuntimeException {

    public CommercialInvariantException(String message) {
        super(message);
    }
}
