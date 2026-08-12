package io.usagecore.controlplane.application.catalogue;

/**
 * Raised when a create/update would violate a uniqueness invariant known to the use case.
 */
public final class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
