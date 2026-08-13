package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record ConsumeUsageRequest(
        @NotBlank String productKey,
        @NotBlank String meterKey,
        @Positive long quantity,
        @NotNull Instant occurredAt,
        @NotBlank String idempotencyKey
) {
}
