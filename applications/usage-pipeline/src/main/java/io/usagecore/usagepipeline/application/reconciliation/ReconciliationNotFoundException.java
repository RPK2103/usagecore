package io.usagecore.usagepipeline.application.reconciliation;

/**
 * Missing reconciliation resource (HTTP 404).
 */
public class ReconciliationNotFoundException extends RuntimeException {

    public ReconciliationNotFoundException(String message) {
        super(message);
    }
}
