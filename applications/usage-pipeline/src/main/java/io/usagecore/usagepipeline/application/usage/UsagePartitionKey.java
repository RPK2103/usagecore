package io.usagecore.usagepipeline.application.usage;

import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic Kafka partition key for usage events:
 * {@code tenantId + productKey + meterKey}.
 * <p>
 * Preserves ordering for one tenant/product/meter stream and distributes different
 * meters/tenants across partitions. A very high-volume tenant/product/meter can still
 * become a hot partition; later evidence may justify a more sophisticated strategy.
 */
public final class UsagePartitionKey {

    private static final char SEPARATOR = '|';

    private UsagePartitionKey() {
    }

    public static String of(UUID tenantId, String productKey, String meterKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(meterKey, "meterKey");
        return tenantId + String.valueOf(SEPARATOR) + productKey + SEPARATOR + meterKey;
    }
}
