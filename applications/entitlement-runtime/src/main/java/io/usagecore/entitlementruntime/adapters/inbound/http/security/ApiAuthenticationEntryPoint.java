package io.usagecore.entitlementruntime.adapters.inbound.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.entitlementruntime.adapters.inbound.http.error.ApiError;
import io.usagecore.entitlementruntime.adapters.inbound.http.error.ApiErrorCodes;
import io.usagecore.entitlementruntime.adapters.inbound.http.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        writeError(response, request, HttpServletResponse.SC_UNAUTHORIZED, ApiErrorCodes.UNAUTHORIZED,
                "Authentication required");
    }

    private void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            int status,
            String errorCode,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlationId = request.getHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER);
        if (correlationId != null) {
            response.setHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER, correlationId);
        }
        ApiError body = new ApiError(
                Instant.now(clock),
                status,
                errorCode,
                message,
                request.getRequestURI(),
                correlationId
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
