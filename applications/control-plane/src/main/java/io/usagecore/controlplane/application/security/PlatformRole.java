package io.usagecore.controlplane.application.security;

/**
 * Control-plane RBAC roles. Names match JWT role claims (without ROLE_ prefix).
 */
public enum PlatformRole {
    PLATFORM_ADMIN,
    CONTRACT_MANAGER,
    TENANT_ADMIN,
    DEVELOPER,
    AUDITOR,
    BILLING_OPERATOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
