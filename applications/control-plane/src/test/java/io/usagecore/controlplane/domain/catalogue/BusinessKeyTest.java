package io.usagecore.controlplane.domain.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "acme",
            "datapilot-cloud",
            "scheduled_exports",
            "enterprise-2026"
    })
    void acceptsConservativeMachineReadableKeys(String key) {
        assertThat(BusinessKey.of(key).value()).isEqualTo(key);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "Acme", "1acme", "-acme", "acme-", "acme__", "acme cloud", "a/b"})
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> BusinessKey.of(key))
                .isInstanceOf(DomainInvariantException.class);
    }
}
