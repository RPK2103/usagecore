package io.usagecore.usagepipeline.adapters.inbound.http.usage;

import io.usagecore.usagepipeline.application.usage.AggregationType;
import io.usagecore.usagepipeline.application.usage.UsageWindowAggregateRecord;
import java.time.Instant;

public record UsageWindowAggregateResponse(
        String productKey,
        String meterKey,
        AggregationType aggregationType,
        Instant windowStart,
        Instant windowEnd,
        long value,
        long eventCount
) {

    public static UsageWindowAggregateResponse from(String productKey, UsageWindowAggregateRecord aggregate) {
        return new UsageWindowAggregateResponse(
                productKey,
                aggregate.meterKey(),
                aggregate.aggregationType(),
                aggregate.windowStart(),
                aggregate.windowEnd(),
                aggregate.aggregateValue(),
                aggregate.eventCount()
        );
    }
}
