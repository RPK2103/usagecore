package io.usagecore.controlplane.application.catalogue;

/**
 * Raised when an application use case cannot locate a required catalogue resource.
 */
public final class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
