package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.AggregationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMeterRequest(
        @NotBlank String meterKey,
        @NotBlank String displayName,
        @NotNull AggregationType aggregationType
) {
}
