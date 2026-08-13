package io.usagecore.controlplane.domain.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommercialPeriodDomainTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void createStartsOpen() {
        CommercialPeriod period = CommercialPeriod.create(TENANT, PRODUCT, START, END);
        assertThat(period.status()).isEqualTo(CommercialPeriodStatus.OPEN);
        assertThat(period.contains(Instant.parse("2026-08-15T12:00:00Z"))).isTrue();
        assertThat(period.contains(END)).isFalse();
        assertThat(period.contains(START)).isTrue();
    }

    @Test
    void validLifecycleTransitions() {
        CommercialPeriod period = CommercialPeriod.create(TENANT, PRODUCT, START, END);
        Instant t1 = Instant.parse("2026-09-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T00:00:00Z");
        Instant t3 = Instant.parse("2026-09-03T00:00:00Z");

        period.beginClosing(t1);
        assertThat(period.status()).isEqualTo(CommercialPeriodStatus.CLOSING);
        assertThat(period.closingStartedAt()).isEqualTo(t1);

        period.beginReconciling(t2);
        assertThat(period.status()).isEqualTo(CommercialPeriodStatus.RECONCILING);

        period.finalizePeriod(t3, "billing-ops");
        assertThat(period.status()).isEqualTo(CommercialPeriodStatus.FINALIZED);
        assertThat(period.finalizedBy()).isEqualTo("billing-ops");
        assertThat(period.finalizedAt()).isEqualTo(t3);
    }

    @Test
    void finalizedIsTerminal() {
        CommercialPeriod period = CommercialPeriod.create(TENANT, PRODUCT, START, END);
        period.beginClosing(Instant.parse("2026-09-01T00:00:00Z"));
        period.beginReconciling(Instant.parse("2026-09-02T00:00:00Z"));
        period.finalizePeriod(Instant.parse("2026-09-03T00:00:00Z"), "admin");

        assertThatThrownBy(() -> period.beginClosing(Instant.parse("2026-09-04T00:00:00Z")))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("FINALIZED commercial period is terminal");
    }

    @Test
    void disallowsSkipTransitions() {
        CommercialPeriod open = CommercialPeriod.create(TENANT, PRODUCT, START, END);
        assertThatThrownBy(() -> open.finalizePeriod(Instant.parse("2026-09-03T00:00:00Z"), "admin"))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("Invalid commercial period transition");

        assertThatThrownBy(() -> open.beginReconciling(Instant.parse("2026-09-02T00:00:00Z")))
                .isInstanceOf(DomainInvariantException.class);

        CommercialPeriod closing = CommercialPeriod.create(TENANT, PRODUCT, START, END);
        closing.beginClosing(Instant.parse("2026-09-01T00:00:00Z"));
        assertThatThrownBy(() -> closing.finalizePeriod(Instant.parse("2026-09-03T00:00:00Z"), "admin"))
                .isInstanceOf(DomainInvariantException.class);
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> CommercialPeriod.create(TENANT, PRODUCT, START, START))
                .isInstanceOf(DomainInvariantException.class);
    }
}
