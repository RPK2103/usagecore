package io.usagecore.usagepipeline.adapters.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.usagepipeline.adapters.observability.ObservabilityMdc;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesIncomingCorrelationIdInResponseAndMdc() throws ServletException, IOException {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "test-correlation-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(ObservabilityMdc.CORRELATION_ID)).isEqualTo("test-correlation-123");
            assertThat(((jakarta.servlet.http.HttpServletRequest) req).getHeader("X-Correlation-Id"))
                    .isEqualTo("test-correlation-123");
        });

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("test-correlation-123");
        assertThat(MDC.get(ObservabilityMdc.CORRELATION_ID)).isNull();
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws ServletException, IOException {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(MDC.get(ObservabilityMdc.CORRELATION_ID)).isNull();
    }
}
