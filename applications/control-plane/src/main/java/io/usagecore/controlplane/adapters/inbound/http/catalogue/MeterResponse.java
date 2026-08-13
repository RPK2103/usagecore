package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.AggregationType;
import io.usagecore.controlplane.domain.catalogue.AggregationWindow;
import io.usagecore.controlplane.domain.catalogue.MeterDefinition;
import io.usagecore.controlplane.domain.catalogue.MeterStatus;
import java.util.UUID;

public record MeterResponse(
        UUID id,
        UUID productId,
        String meterKey,
        String displayName,
        AggregationType aggregationType,
        AggregationWindow aggregationWindow,
        MeterStatus status
) {

    public static MeterResponse from(MeterDefinition meter) {
        return new MeterResponse(
                meter.id(),
                meter.productId(),
                meter.meterKey().value(),
                meter.displayName(),
                meter.aggregationType(),
                meter.aggregationWindow(),
                meter.status()
        );
    }
}
