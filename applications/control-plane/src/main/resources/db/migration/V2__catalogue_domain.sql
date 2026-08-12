-- Phase 1B catalogue domain tables.
-- Contract / ContractVersion / Entitlement deferred.

CREATE TABLE tenant (
    id              UUID PRIMARY KEY,
    tenant_key      VARCHAR(64)  NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_tenant_tenant_key UNIQUE (tenant_key),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_tenant_display_name_nonblank CHECK (btrim(display_name) <> '')
);

CREATE TABLE product (
    id              UUID PRIMARY KEY,
    product_key     VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_product_product_key UNIQUE (product_key),
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_product_name_nonblank CHECK (btrim(name) <> '')
);

CREATE TABLE feature (
    id              UUID PRIMARY KEY,
    product_id      UUID         NOT NULL REFERENCES product (id),
    feature_key     VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_feature_product_feature_key UNIQUE (product_id, feature_key),
    CONSTRAINT ck_feature_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_feature_name_nonblank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_feature_product_id ON feature (product_id);

CREATE TABLE plan (
    id              UUID PRIMARY KEY,
    product_id      UUID         NOT NULL REFERENCES product (id),
    plan_key        VARCHAR(64)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_plan_product_plan_key UNIQUE (product_id, plan_key),
    CONSTRAINT ck_plan_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_plan_name_nonblank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_plan_product_id ON plan (product_id);

CREATE TABLE plan_feature (
    id                  UUID PRIMARY KEY,
    plan_id             UUID        NOT NULL REFERENCES plan (id),
    feature_id          UUID        NOT NULL REFERENCES feature (id),
    entitlement_mode    VARCHAR(32) NOT NULL,
    limit_quantity      BIGINT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_plan_feature_plan_feature UNIQUE (plan_id, feature_id),
    CONSTRAINT ck_plan_feature_entitlement_mode
        CHECK (entitlement_mode IN ('ENABLED', 'DISABLED', 'LIMITED')),
    CONSTRAINT ck_plan_feature_limit_quantity
        CHECK (
            (entitlement_mode = 'LIMITED' AND limit_quantity IS NOT NULL AND limit_quantity > 0)
            OR (entitlement_mode <> 'LIMITED' AND limit_quantity IS NULL)
        )
);

CREATE INDEX idx_plan_feature_plan_id ON plan_feature (plan_id);
