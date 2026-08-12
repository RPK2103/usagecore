package io.usagecore.entitlementruntime.adapters.inbound.http.error;

import java.time.Instant;

public record ApiError(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        String correlationId
) {
}
