package io.usagecore.entitlementruntime.adapters.inbound.http;

import io.usagecore.entitlementruntime.adapters.inbound.http.error.GlobalExceptionHandler;
import io.usagecore.entitlementruntime.adapters.observability.ObservabilityMdc;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ensures every request has an {@code X-Correlation-Id}, generating one when absent.
 * Puts {@code correlationId} in MDC for the request and restores it afterwards.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER);
        final String correlationId =
                (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;

        HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if (GlobalExceptionHandler.CORRELATION_ID_HEADER.equalsIgnoreCase(name)) {
                    return correlationId;
                }
                return super.getHeader(name);
            }
        };

        response.setHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER, correlationId);
        try (ObservabilityMdc.Scope ignored = ObservabilityMdc.open(
                ObservabilityMdc.CORRELATION_ID,
                correlationId
        )) {
            filterChain.doFilter(wrappedRequest, response);
        }
    }
}
