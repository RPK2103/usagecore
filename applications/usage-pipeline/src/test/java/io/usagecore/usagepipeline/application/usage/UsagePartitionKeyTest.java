package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsagePartitionKeyTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void sameTenantProductMeter_producesSameKey() {
        String first = UsagePartitionKey.of(TENANT_A, "datapilot-cloud", "scheduled_export");
        String second = UsagePartitionKey.of(TENANT_A, "datapilot-cloud", "scheduled_export");
        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("11111111-1111-1111-1111-111111111111|datapilot-cloud|scheduled_export");
    }

    @Test
    void differentTenant_producesDifferentKey() {
        String a = UsagePartitionKey.of(TENANT_A, "datapilot-cloud", "scheduled_export");
        String b = UsagePartitionKey.of(TENANT_B, "datapilot-cloud", "scheduled_export");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentProduct_producesDifferentKey() {
        String a = UsagePartitionKey.of(TENANT_A, "datapilot-cloud", "scheduled_export");
        String b = UsagePartitionKey.of(TENANT_A, "other-product", "scheduled_export");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentMeter_producesDifferentKey() {
        String a = UsagePartitionKey.of(TENANT_A, "datapilot-cloud", "scheduled_export");
        String b = UsagePartitionKey.of(TENANT_A, "datapilot-cloud", "api_calls");
        assertThat(a).isNotEqualTo(b);
    }
}
