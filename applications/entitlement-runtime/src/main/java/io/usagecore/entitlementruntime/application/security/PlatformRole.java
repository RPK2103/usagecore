package io.usagecore.entitlementruntime.application.security;

/**
 * Runtime RBAC roles. Names match JWT role claims (without ROLE_ prefix).
 * Independent copy of the UsageCore identity contract — no Control Plane dependency.
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
