package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.CommercialPeriod;
import java.time.Instant;
import java.util.UUID;

public record CommercialPeriodResponse(
        UUID id,
        UUID tenantId,
        UUID productId,
        Instant periodStart,
        Instant periodEnd,
        String status,
        Instant closingStartedAt,
        Instant reconcilingStartedAt,
        Instant finalizedAt,
        String finalizedBy
) {

    public static CommercialPeriodResponse from(CommercialPeriod period) {
        return new CommercialPeriodResponse(
                period.id(),
                period.tenantId(),
                period.productId(),
                period.periodStart(),
                period.periodEnd(),
                period.status().name(),
                period.closingStartedAt(),
                period.reconcilingStartedAt(),
                period.finalizedAt(),
                period.finalizedBy()
        );
    }
}
