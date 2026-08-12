package io.usagecore.controlplane.adapters.inbound.http.security;

import io.usagecore.controlplane.adapters.inbound.http.error.GlobalExceptionHandler;
import io.usagecore.controlplane.application.security.CorrelationIdAccessor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestCorrelationIdAccessor implements CorrelationIdAccessor {

    @Override
    public String currentCorrelationId() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return request.getHeader(GlobalExceptionHandler.CORRELATION_ID_HEADER);
    }
}
