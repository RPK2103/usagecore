package io.usagecore.controlplane.application.security;

/**
 * Optional correlation id for audit/error paths outside HTTP adapters.
 */
public interface CorrelationIdAccessor {

    String currentCorrelationId();
}
