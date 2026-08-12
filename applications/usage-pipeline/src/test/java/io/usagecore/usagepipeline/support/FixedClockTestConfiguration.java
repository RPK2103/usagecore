package io.usagecore.usagepipeline.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FixedClockTestConfiguration {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-08-12T15:00:00Z");

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
