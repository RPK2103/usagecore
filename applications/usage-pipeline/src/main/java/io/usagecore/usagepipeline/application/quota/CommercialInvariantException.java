package io.usagecore.usagepipeline.application.quota;

/**
 * Raised when commercial snapshot rows violate invariants (e.g. ambiguous effective contracts).
 */
public class CommercialInvariantException extends RuntimeException {

    public CommercialInvariantException(String message) {
        super(message);
    }
}
