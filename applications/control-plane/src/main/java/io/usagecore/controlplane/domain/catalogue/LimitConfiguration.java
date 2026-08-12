package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;

/**
 * Minimal strongly typed limit for {@link EntitlementMode#LIMITED}.
 * Not a generic rule engine or expression language.
 */
public final class LimitConfiguration {

    private final long maxQuantity;

    private LimitConfiguration(long maxQuantity) {
        this.maxQuantity = maxQuantity;
    }

    public static LimitConfiguration ofMaxQuantity(long maxQuantity) {
        if (maxQuantity <= 0) {
            throw new DomainInvariantException("LIMITED configuration requires a positive maxQuantity");
        }
        return new LimitConfiguration(maxQuantity);
    }

    public long maxQuantity() {
        return maxQuantity;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LimitConfiguration that)) {
            return false;
        }
        return maxQuantity == that.maxQuantity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxQuantity);
    }

    @Override
    public String toString() {
        return "LimitConfiguration{maxQuantity=" + maxQuantity + '}';
    }
}
