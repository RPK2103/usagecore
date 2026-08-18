package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Usage ingestion request. tenantId is intentionally absent — unknown fields are rejected.
 * Meter existence/status is enforced in the application service, not by this DTO.
 */
public record SubmitUsageEventRequest(
        @NotBlank String productKey,
        @NotBlank String meterKey,
        @NotNull @Positive Long quantity,
        @NotNull Instant occurredAt,
        @NotBlank @Size(max = 128) String idempotencyKey
) {
}
