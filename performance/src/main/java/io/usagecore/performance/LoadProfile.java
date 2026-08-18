package io.usagecore.performance;

import java.util.Locale;

/**
 * Explicit load shapes. Warm-up is a separate profile and must not be mixed
 * into headline latency numbers.
 */
public enum LoadProfile {
    WARMUP,
    SMOKE,
    BASELINE,
    RAMP,
    SUSTAINED,
    BURST,
    CONTENTION;

    public static LoadProfile from(String raw) {
        if (raw == null || raw.isBlank()) {
            return SMOKE;
        }
        return LoadProfile.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
