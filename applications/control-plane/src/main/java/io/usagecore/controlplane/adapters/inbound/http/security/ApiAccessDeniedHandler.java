package io.usagecore.controlplane.adapters.inbound.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.usagecore.controlplane.adapters.inbound.http.error.ApiError;
import io.usagecore.controlplane.adapters.inbound.http.error.ApiErrorCodes;
import io.usagecore.controlplane.adapters.inbound.http.error.GlobalExceptionHandler;
import io.usagecore.controlplane.application.security.SecurityAuditEventType;
import io.usagecore.controlplane.application.security.SecurityAuditRecord;
import io.usagecore.controlplane.application.security.SecurityAuditRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final SecurityAuditRecorder securityAuditRecorder;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper, SecurityAuditRecorder securityAuditRecorder) {
        this.objectMapper = objectMapper;
        this.securityAuditRecorder = securityAuditRecorder;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        recordInsufficientRole(request);
        writeError(response, request, HttpServletResponse.SC_FORBIDDEN, ApiErrorCodes.FORBIDDEN, "Access denied");
    }

    private void recordInsufficientRole(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principalId = "anonymous";
        UUID tenantId = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            principalId = jwt.getSubject();
            String tenantClaim = jwt.getClaimAsString(SecurityContextCurrentPrincipal.TENANT_ID_CLAIM);
            if (tenantClaim != null && !tenantClaim.isBlank()) {
                try {
                    tenantId = UUID.fromString(tenantClaim);
                } catch (IllegalArgumentException ignored) {
                    // ignore malformed claim for audit identity fields
                }
            }
        }
        securityAuditRecorder.append(new SecurityAuditRecord(
                Instant.now(),
                SecurityAuditEventType.INSUFFICIENT_ROLE,
                principalId,
                tenantId,
                request.getMethod() + " " + request.getRequestURI(),
                "HttpEndpoint",
                request.getRequestURI(),
                request.getHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER),
                "Insufficient role for endpoint"
        ));
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
                Instant.now(),
                status,
                errorCode,
                message,
                request.getRequestURI(),
                correlationId
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
