package io.usagecore.usagepipeline.adapters.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Restores W3C context from a persisted outbox envelope and starts {@code usage.outbox.publish}.
 * The original HTTP span has already ended; this is an asynchronous continuation, not one
 * synchronous span stretched across the outbox delay.
 */
@Component
public class OutboxPublishSpanSupport {

    public static final String SPAN_NAME = "usage.outbox.publish";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublishSpanSupport.class);

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxPublishSpanSupport(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public Scope open(String storedTraceEvidence) {
        try {
            Span span = startSpan(storedTraceEvidence);
            Tracer.SpanInScope inScope = tracer.withSpan(span);
            return new Scope(span, inScope);
        } catch (RuntimeException ex) {
            log.debug("Outbox publish span could not be started; publishing continues", ex);
            return Scope.noop();
        }
    }

    private Span startSpan(String storedTraceEvidence) {
        if (W3cTraceContext.isTraceparent(storedTraceEvidence)) {
            Span.Builder extracted = propagator.extract(
                    Map.of(W3cTraceContext.TRACEPARENT_HEADER, storedTraceEvidence),
                    Map::get
            );
            return extracted.name(SPAN_NAME).start();
        }
        return tracer.nextSpan().name(SPAN_NAME).start();
    }

    public static final class Scope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope inScope;

        private Scope(Span span, Tracer.SpanInScope inScope) {
            this.span = span;
            this.inScope = inScope;
        }

        static Scope noop() {
            return new Scope(null, null);
        }

        @Override
        public void close() {
            try {
                if (inScope != null) {
                    inScope.close();
                }
            } catch (RuntimeException ignored) {
                // Tracing must not fail publish.
            }
            try {
                if (span != null) {
                    span.end();
                }
            } catch (RuntimeException ignored) {
                // Tracing must not fail publish.
            }
        }
    }
}
