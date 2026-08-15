package io.usagecore.entitlementruntime.adapters.observability;

import org.slf4j.MDC;

/**
 * Scoped MDC updates that restore prior values so pooled threads do not leak context.
 */
public final class ObservabilityMdc {

    public static final String CORRELATION_ID = "correlationId";
    public static final String TENANT_ID = "tenantId";

    private ObservabilityMdc() {
    }

    public static Scope open(String key, String value) {
        String previous = MDC.get(key);
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
        return () -> {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
