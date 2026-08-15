package io.usagecore.usagepipeline.adapters.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.usagecore.usagepipeline.application.observability.TraceContextPort;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MicrometerTraceContextAdapter implements TraceContextPort {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerTraceContextAdapter(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }

    @Override
    public String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        String traceparent = carrier.get(TRACEPARENT);
        return traceparent == null || traceparent.isBlank() ? null : traceparent;
    }
}
