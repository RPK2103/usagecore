package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageAggregateRecord;
import java.time.Instant;

public record UsageAggregateResponse(
        String productKey,
        String meterKey,
        AggregationType aggregationType,
        long value,
        long eventCount,
        Instant lastEventAt
) {

    public static UsageAggregateResponse from(String productKey, UsageAggregateRecord aggregate) {
        return new UsageAggregateResponse(
                productKey,
                aggregate.meterKey(),
                aggregate.aggregationType(),
                aggregate.aggregateValue(),
                aggregate.eventCount(),
                aggregate.lastEventAt()
        );
    }
}
