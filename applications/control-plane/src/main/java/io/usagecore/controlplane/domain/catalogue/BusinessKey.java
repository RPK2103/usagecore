package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, immutable machine-readable business key.
 * Examples: {@code acme}, {@code datapilot-cloud}, {@code scheduled_exports}, {@code enterprise-2026}.
 */
public final class BusinessKey {

    private static final int MAX_LENGTH = 64;
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9]*([_-][a-z0-9]+)*$");

    private final String value;

    private BusinessKey(String value) {
        this.value = value;
    }

    public static BusinessKey of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainInvariantException("Business key must be non-blank");
        }
        if (!raw.equals(raw.trim())) {
            throw new DomainInvariantException("Business key must not have leading or trailing whitespace");
        }
        if (raw.length() > MAX_LENGTH) {
            throw new DomainInvariantException("Business key must be at most " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(raw).matches()) {
            throw new DomainInvariantException(
                    "Business key must be lowercase alphanumeric segments separated by '-' or '_'"
            );
        }
        return new BusinessKey(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BusinessKey that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
