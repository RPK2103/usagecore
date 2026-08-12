package io.usagecore.controlplane.application.security;

/**
 * Authenticated caller lacks authorization for the requested operation.
 */
public class AuthorizationDeniedException extends RuntimeException {

    public AuthorizationDeniedException(String message) {
        super(message);
    }
}
