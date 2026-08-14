package io.usagecore.usagepipeline.adapters.inbound.http.reconciliation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApplyUsageAdjustmentRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotBlank @Size(max = 512) String reason
) {
}
