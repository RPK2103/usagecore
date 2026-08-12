package io.usagecore.controlplane.domain.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantTest {

    @Test
    void createsActiveTenantWithValidKeyAndDisplayName() {
        Tenant tenant = Tenant.create(BusinessKey.of("acme"), "Acme Corp");

        assertThat(tenant.id()).isNotNull();
        assertThat(tenant.tenantKey().value()).isEqualTo("acme");
        assertThat(tenant.displayName()).isEqualTo("Acme Corp");
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void rejectsBlankDisplayName() {
        assertThatThrownBy(() -> Tenant.create(BusinessKey.of("acme"), "  "))
                .isInstanceOf(DomainInvariantException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void rejectsInvalidTenantKey() {
        assertThatThrownBy(() -> Tenant.create(BusinessKey.of("Acme"), "Acme Corp"))
                .isInstanceOf(DomainInvariantException.class);
    }
}
