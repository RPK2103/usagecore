package io.usagecore.controlplane.adapters.inbound.http.error;

import java.time.Instant;

/**
 * Consistent HTTP error payload for control-plane APIs.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        String correlationId
) {
}
