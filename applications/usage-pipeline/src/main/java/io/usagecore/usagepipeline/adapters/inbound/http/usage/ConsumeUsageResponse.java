package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import io.usagecore.usagepipeline.application.quota.QuotaConsumeResult;
import io.usagecore.usagepipeline.application.quota.QuotaDecision;
import java.util.UUID;

public record ConsumeUsageResponse(
        UUID consumptionId,
        UUID eventId,
        QuotaDecision decision,
        String reason,
        String productKey,
        String meterKey,
        String featureKey,
        long quantity,
        Long configuredLimit,
        Long consumed,
        Long remaining,
        Integer contractVersionNumber,
        String correlationId,
        boolean idempotentReplay
) {

    public static ConsumeUsageResponse from(QuotaConsumeResult result) {
        return new ConsumeUsageResponse(
                result.consumptionId(),
                result.eventId(),
                result.decision(),
                result.reason(),
                result.productKey(),
                result.meterKey(),
                result.featureKey(),
                result.quantity(),
                result.configuredLimit(),
                result.consumed(),
                result.remaining(),
                result.contractVersionNumber(),
                result.correlationId(),
                result.idempotentReplay()
        );
    }
}
