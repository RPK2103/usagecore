package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.AggregationType;
import io.usagecore.controlplane.domain.catalogue.AggregationWindow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateMeterRequest(
        @NotBlank String meterKey,
        @NotBlank String displayName,
        @NotNull UUID featureId,
        @NotNull AggregationType aggregationType,
        @NotNull AggregationWindow aggregationWindow
) {
}
