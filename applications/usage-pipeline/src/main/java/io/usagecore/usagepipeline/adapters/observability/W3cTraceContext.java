package io.usagecore.usagepipeline.adapters.observability;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * W3C traceparent helpers for outbox evidence and Kafka continuation.
 */
public final class W3cTraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";
    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final Pattern HEX_TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private W3cTraceContext() {
    }

    public static boolean isTraceparent(String value) {
        return value != null && TRACEPARENT.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public static boolean isHexTraceId(String value) {
        return value != null && HEX_TRACE_ID.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }

    public static String hexTraceId(String traceparentOrTraceId) {
        if (isTraceparent(traceparentOrTraceId)) {
            return traceparentOrTraceId.substring(3, 35);
        }
        if (isHexTraceId(traceparentOrTraceId)) {
            return traceparentOrTraceId.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
