-- Phase 3: append-oriented entitlement decision evidence for the Entitlement Runtime.
-- Contractual configuration only — no remainingQuota / consumedUnits / reservedUnits.
-- Never store JWTs, access tokens, passwords, or client secrets in this table.

CREATE TABLE entitlement_decision (
    decision_id               UUID PRIMARY KEY,
    tenant_id                 UUID         NOT NULL,
    principal_id              VARCHAR(255) NOT NULL,
    contract_id               UUID,
    contract_version_id       UUID,
    contract_version_number   INTEGER,
    product_key               VARCHAR(64)  NOT NULL,
    feature_key               VARCHAR(64)  NOT NULL,
    requested_units           BIGINT       NOT NULL,
    decision                  VARCHAR(32)  NOT NULL,
    reason                    VARCHAR(64)  NOT NULL,
    configured_limit          BIGINT,
    evaluated_at              TIMESTAMPTZ  NOT NULL,
    correlation_id            VARCHAR(128),
    created_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_entitlement_decision_decision CHECK (
        decision IN ('ALLOW', 'DENY', 'ALLOW_WITH_LIMIT')
    ),
    CONSTRAINT ck_entitlement_decision_requested_units CHECK (requested_units > 0),
    CONSTRAINT ck_entitlement_decision_version_number CHECK (
        contract_version_number IS NULL OR contract_version_number > 0
    ),
    CONSTRAINT ck_entitlement_decision_configured_limit CHECK (
        configured_limit IS NULL OR configured_limit > 0
    )
);

CREATE INDEX idx_entitlement_decision_tenant_evaluated
    ON entitlement_decision (tenant_id, evaluated_at);

CREATE INDEX idx_entitlement_decision_correlation_id
    ON entitlement_decision (correlation_id);

CREATE INDEX idx_entitlement_decision_created_at
    ON entitlement_decision (created_at);
