-- Phase 2A: append-only security audit evidence.
-- Never store JWTs, access tokens, passwords, or client secrets in this table.

CREATE TABLE security_audit_event (
    id                        UUID PRIMARY KEY,
    occurred_at               TIMESTAMPTZ  NOT NULL,
    event_type                VARCHAR(64)  NOT NULL,
    principal_id              VARCHAR(255) NOT NULL,
    authenticated_tenant_id   UUID,
    action                    VARCHAR(256) NOT NULL,
    resource_type             VARCHAR(64),
    resource_id               VARCHAR(128),
    correlation_id            VARCHAR(128),
    detail                    VARCHAR(1024),
    CONSTRAINT ck_security_audit_event_type CHECK (
        event_type IN ('CROSS_TENANT_ACCESS_DENIED', 'INSUFFICIENT_ROLE')
    )
);

CREATE INDEX idx_security_audit_event_occurred_at ON security_audit_event (occurred_at);
CREATE INDEX idx_security_audit_event_principal_id ON security_audit_event (principal_id);
CREATE INDEX idx_security_audit_event_tenant_id ON security_audit_event (authenticated_tenant_id);
