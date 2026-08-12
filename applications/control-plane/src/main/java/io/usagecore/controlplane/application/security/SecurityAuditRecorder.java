package io.usagecore.controlplane.application.security;

/**
 * Append-only security audit evidence. Must never receive tokens or secrets.
 */
public interface SecurityAuditRecorder {

    void append(SecurityAuditRecord record);
}
