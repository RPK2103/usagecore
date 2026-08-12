package io.usagecore.controlplane.domain.catalogue;

import java.util.Objects;
import java.util.UUID;

/**
 * Tenant isolation boundary. Soft lifecycle only — no physical delete in this milestone.
 */
public final class Tenant {

    private final UUID id;
    private final BusinessKey tenantKey;
    private String displayName;
    private TenantStatus status;

    private Tenant(UUID id, BusinessKey tenantKey, String displayName, TenantStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        this.displayName = DisplayNames.requireNonBlank(displayName, "displayName");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static Tenant create(BusinessKey tenantKey, String displayName) {
        return new Tenant(UUID.randomUUID(), tenantKey, displayName, TenantStatus.ACTIVE);
    }

    public static Tenant reconstitute(UUID id, BusinessKey tenantKey, String displayName, TenantStatus status) {
        return new Tenant(id, tenantKey, displayName, status);
    }

    public void rename(String displayName) {
        assertNotArchived();
        this.displayName = DisplayNames.requireNonBlank(displayName, "displayName");
    }

    public void suspend() {
        assertNotArchived();
        this.status = TenantStatus.SUSPENDED;
    }

    public void activate() {
        assertNotArchived();
        this.status = TenantStatus.ACTIVE;
    }

    public void archive() {
        this.status = TenantStatus.ARCHIVED;
    }

    private void assertNotArchived() {
        if (status == TenantStatus.ARCHIVED) {
            throw new DomainInvariantException("ARCHIVED tenant cannot be modified");
        }
    }

    public UUID id() {
        return id;
    }

    public BusinessKey tenantKey() {
        return tenantKey;
    }

    public String displayName() {
        return displayName;
    }

    public TenantStatus status() {
        return status;
    }
}
