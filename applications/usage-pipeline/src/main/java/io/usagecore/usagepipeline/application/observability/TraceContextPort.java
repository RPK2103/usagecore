package io.usagecore.usagepipeline.application.observability;

/**
 * Captures the current distributed trace identity for durable outbox evidence.
 * Implemented in adapters; domain must not depend on tracing APIs.
 */
public interface TraceContextPort {

    /**
     * Hex OpenTelemetry trace id, or {@code null} when no span is in scope.
     */
    String currentTraceId();

    /**
     * W3C {@code traceparent} for async continuation, or {@code null} when unavailable.
     */
    String currentTraceparent();
}
