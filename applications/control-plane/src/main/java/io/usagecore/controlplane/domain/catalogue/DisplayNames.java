package io.usagecore.controlplane.domain.catalogue;

final class DisplayNames {

    private DisplayNames() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainInvariantException(fieldName + " must be non-blank");
        }
        return value.trim();
    }
}
