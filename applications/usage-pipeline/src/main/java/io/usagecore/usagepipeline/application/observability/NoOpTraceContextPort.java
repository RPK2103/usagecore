package io.usagecore.usagepipeline.application.observability;

/**
 * No-op trace context for unit tests and paths without a current span.
 */
public final class NoOpTraceContextPort implements TraceContextPort {

    public static final NoOpTraceContextPort INSTANCE = new NoOpTraceContextPort();

    private NoOpTraceContextPort() {
    }

    @Override
    public String currentTraceId() {
        return null;
    }

    @Override
    public String currentTraceparent() {
        return null;
    }
}
