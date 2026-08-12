package io.usagecore.usagepipeline.application.security;

public class AuthorizationDeniedException extends RuntimeException {

    public AuthorizationDeniedException(String message) {
        super(message);
    }
}
