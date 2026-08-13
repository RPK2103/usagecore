package io.usagecore.usagepipeline.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UsageWindowResolverTest {

    private final UsageWindowResolver resolver = new UsageWindowResolver();

    @Test
    void monthly_firstInstantOfAugust_belongsToAugust() {
        UsageWindow window = resolver.resolve(
                Instant.parse("2026-08-01T00:00:00Z"),
                AggregationWindow.MONTHLY
        );
        assertThat(window.start()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(window.contains(Instant.parse("2026-08-01T00:00:00Z"))).isTrue();
    }

    @Test
    void monthly_lastInstantBeforeSeptember_belongsToAugust() {
        UsageWindow window = resolver.resolve(
                Instant.parse("2026-08-31T23:59:59.999Z"),
                AggregationWindow.MONTHLY
        );
        assertThat(window.start()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void monthly_exactSeptemberStart_belongsToSeptember() {
        Instant boundary = Instant.parse("2026-09-01T00:00:00Z");
        UsageWindow window = resolver.resolve(boundary, AggregationWindow.MONTHLY);
        assertThat(window.start()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(window.contains(boundary)).isTrue();

        UsageWindow august = resolver.resolve(
                Instant.parse("2026-08-31T23:59:59Z"),
                AggregationWindow.MONTHLY
        );
        assertThat(august.contains(boundary)).isFalse();
    }

    @Test
    void daily_resolvesUtcDayHalfOpen() {
        UsageWindow window = resolver.resolve(
                Instant.parse("2026-08-12T14:30:00Z"),
                AggregationWindow.DAILY
        );
        assertThat(window.start()).isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-08-13T00:00:00Z"));
    }

    @Test
    void late_whenProcessedAtOrAfterWindowEnd() {
        UsageWindow august = resolver.resolve(
                Instant.parse("2026-08-28T10:00:00Z"),
                AggregationWindow.MONTHLY
        );
        assertThat(august.isLate(Instant.parse("2026-09-01T00:00:00Z"))).isTrue();
        assertThat(august.isLate(Instant.parse("2026-09-03T12:00:00Z"))).isTrue();
        assertThat(august.isLate(Instant.parse("2026-08-31T23:59:59Z"))).isFalse();
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> resolver.resolve(null, AggregationWindow.MONTHLY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> resolver.resolve(Instant.parse("2026-08-01T00:00:00Z"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
