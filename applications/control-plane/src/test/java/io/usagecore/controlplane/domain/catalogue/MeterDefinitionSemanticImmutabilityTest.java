package io.usagecore.controlplane.domain.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Semantic metering fields are final after construction — no mutators exist.
 */
class MeterDefinitionSemanticImmutabilityTest {

    @Test
    void aggregationTypeAggregationWindowAndMeterKeyHaveNoMutators() throws Exception {
        assertThat(MeterDefinition.class.getDeclaredField("aggregationType").getModifiers() & Modifier.FINAL)
                .isNotZero();
        assertThat(MeterDefinition.class.getDeclaredField("aggregationWindow").getModifiers() & Modifier.FINAL)
                .isNotZero();
        assertThat(MeterDefinition.class.getDeclaredField("meterKey").getModifiers() & Modifier.FINAL)
                .isNotZero();
        assertThat(MeterDefinition.class.getDeclaredMethods())
                .noneMatch(m -> m.getName().startsWith("setAggregation")
                        || m.getName().equals("changeAggregationType")
                        || m.getName().equals("changeAggregationWindow")
                        || m.getName().equals("renameMeterKey"));
    }

    @Test
    void createPersistsRequestedWindowSemantics() {
        Product product = Product.create(BusinessKey.of("datapilot-cloud"), "DataPilot Cloud");
        MeterDefinition meter = MeterDefinition.create(
                product,
                BusinessKey.of("api_requests"),
                "API Requests",
                AggregationType.SUM,
                AggregationWindow.MONTHLY
        );
        assertThat(meter.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(meter.aggregationWindow()).isEqualTo(AggregationWindow.MONTHLY);
        assertThat(meter.meterKey().value()).isEqualTo("api_requests");
    }
}
