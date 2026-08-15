package io.usagecore.usagepipeline.application.observability;

/**
 * Envelope {@code traceId} holds W3C {@code traceparent} when captured at acceptance,
 * otherwise a 32-char hex trace id, otherwise null. Correlation id remains separate.
 */
public final class TraceEvidence {

    private TraceEvidence() {
    }

    public static String forEnvelope(TraceContextPort port) {
        if (port == null) {
            return null;
        }
        String traceparent = port.currentTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            return traceparent;
        }
        String traceId = port.currentTraceId();
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return null;
    }
}
