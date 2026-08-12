package io.usagecore.controlplane.application.security;

/**
 * Request-scoped access to the authenticated principal.
 * Implementations must resolve from the security context — not static ThreadLocal state.
 */
public interface CurrentPrincipal {

    AuthenticatedPrincipal require();
}
