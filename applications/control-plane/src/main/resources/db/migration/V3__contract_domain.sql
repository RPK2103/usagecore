-- Phase 1C contract domain tables.
-- btree_gist enables PostgreSQL exclusion constraints on tstzrange for activated
-- contract version intervals (concurrent-safe non-overlap enforcement).

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE contract (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    product_id      UUID         NOT NULL REFERENCES product (id),
    contract_key    VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_contract_tenant_product UNIQUE (tenant_id, product_id),
    CONSTRAINT uq_contract_tenant_contract_key UNIQUE (tenant_id, contract_key),
    CONSTRAINT ck_contract_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_contract_tenant_id ON contract (tenant_id);
CREATE INDEX idx_contract_product_id ON contract (product_id);

CREATE TABLE contract_version (
    id                  UUID PRIMARY KEY,
    contract_id         UUID         NOT NULL REFERENCES contract (id),
    tenant_id           UUID         NOT NULL REFERENCES tenant (id),
    version_number      INTEGER      NOT NULL,
    source_plan_id      UUID         REFERENCES plan (id),
    status              VARCHAR(32)  NOT NULL,
    effective_from      TIMESTAMPTZ  NOT NULL,
    effective_until     TIMESTAMPTZ,
    activated_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_contract_version_contract_version_number UNIQUE (contract_id, version_number),
    CONSTRAINT ck_contract_version_status CHECK (status IN ('DRAFT', 'ACTIVATED')),
    CONSTRAINT ck_contract_version_version_number_positive CHECK (version_number > 0),
    CONSTRAINT ck_contract_version_effective_interval CHECK (
        effective_until IS NULL OR effective_until > effective_from
    ),
    CONSTRAINT ck_contract_version_activated_at CHECK (
        (status = 'DRAFT' AND activated_at IS NULL)
        OR (status = 'ACTIVATED' AND activated_at IS NOT NULL)
    ),
    CONSTRAINT ex_contract_version_activated_no_overlap EXCLUDE USING gist (
        contract_id WITH =,
        tstzrange(effective_from, effective_until, '[)') WITH &&
    ) WHERE (status = 'ACTIVATED')
);

CREATE INDEX idx_contract_version_contract_id ON contract_version (contract_id);
CREATE INDEX idx_contract_version_tenant_id ON contract_version (tenant_id);
CREATE INDEX idx_contract_version_effective_from ON contract_version (contract_id, effective_from);

CREATE TABLE entitlement (
    id                  UUID PRIMARY KEY,
    contract_version_id UUID         NOT NULL REFERENCES contract_version (id),
    feature_id          UUID         NOT NULL REFERENCES feature (id),
    entitlement_mode    VARCHAR(32)  NOT NULL,
    limit_quantity      BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_entitlement_version_feature UNIQUE (contract_version_id, feature_id),
    CONSTRAINT ck_entitlement_mode CHECK (entitlement_mode IN ('ENABLED', 'DISABLED', 'LIMITED')),
    CONSTRAINT ck_entitlement_limit_quantity CHECK (
        (entitlement_mode = 'LIMITED' AND limit_quantity IS NOT NULL AND limit_quantity > 0)
        OR (entitlement_mode <> 'LIMITED' AND limit_quantity IS NULL)
    )
);

CREATE INDEX idx_entitlement_contract_version_id ON entitlement (contract_version_id);
CREATE INDEX idx_entitlement_feature_id ON entitlement (feature_id);
