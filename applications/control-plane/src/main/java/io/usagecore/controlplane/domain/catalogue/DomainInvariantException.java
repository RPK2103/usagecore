package io.usagecore.controlplane.domain.catalogue;

/**
 * Raised when a catalogue domain invariant is violated.
 */
public final class DomainInvariantException extends RuntimeException {

    public DomainInvariantException(String message) {
        super(message);
    }
}
