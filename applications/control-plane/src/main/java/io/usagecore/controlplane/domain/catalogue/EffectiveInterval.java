package io.usagecore.controlplane.domain.catalogue;

import java.time.Instant;
import java.util.Objects;

/**
 * Half-open commercial interval {@code [effectiveFrom, effectiveUntil)}.
 * A null {@code effectiveUntil} means open-ended.
 */
public final class EffectiveInterval {

    private final Instant effectiveFrom;
    private final Instant effectiveUntil;

    private EffectiveInterval(Instant effectiveFrom, Instant effectiveUntil) {
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new DomainInvariantException("effectiveUntil must be strictly after effectiveFrom");
        }
        this.effectiveUntil = effectiveUntil;
    }

    public static EffectiveInterval of(Instant effectiveFrom, Instant effectiveUntil) {
        return new EffectiveInterval(effectiveFrom, effectiveUntil);
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveUntil() {
        return effectiveUntil;
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (instant.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveUntil == null || instant.isBefore(effectiveUntil);
    }

    public boolean overlaps(EffectiveInterval other) {
        Objects.requireNonNull(other, "other");
        Instant thisEnd = effectiveUntil == null ? Instant.MAX : effectiveUntil;
        Instant otherEnd = other.effectiveUntil == null ? Instant.MAX : other.effectiveUntil;
        return effectiveFrom.isBefore(otherEnd) && other.effectiveFrom.isBefore(thisEnd);
    }
}
