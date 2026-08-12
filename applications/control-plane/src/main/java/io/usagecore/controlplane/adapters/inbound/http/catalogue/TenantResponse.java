package io.usagecore.controlplane.adapters.inbound.http.catalogue;

import io.usagecore.controlplane.domain.catalogue.Tenant;
import io.usagecore.controlplane.domain.catalogue.TenantStatus;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String tenantKey,
        String displayName,
        TenantStatus status
) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.id(),
                tenant.tenantKey().value(),
                tenant.displayName(),
                tenant.status()
        );
    }
}
