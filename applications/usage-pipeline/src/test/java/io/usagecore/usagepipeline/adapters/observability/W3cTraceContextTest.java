package io.usagecore.usagepipeline.adapters.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class W3cTraceContextTest {

    @Test
    void parsesTraceparentAndHexTraceId() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        assertThat(W3cTraceContext.isTraceparent(traceparent)).isTrue();
        assertThat(W3cTraceContext.hexTraceId(traceparent)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(W3cTraceContext.isHexTraceId("4bf92f3577b34da6a3ce929d0e0e4736")).isTrue();
        assertThat(W3cTraceContext.isTraceparent("corr-1")).isFalse();
    }
}
