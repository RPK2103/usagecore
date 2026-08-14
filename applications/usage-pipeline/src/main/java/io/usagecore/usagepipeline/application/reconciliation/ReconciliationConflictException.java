package io.usagecore.usagepipeline.application.reconciliation;

/**
 * Conflict / invariant failure for reconciliation (HTTP 409).
 */
public class ReconciliationConflictException extends RuntimeException {

    public ReconciliationConflictException(String message) {
        super(message);
    }
}
