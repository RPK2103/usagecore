-- Phase 6C: contract-aware quota enforcement.
-- MeterDefinition gains an explicit governing Feature (meter → feature).
-- Legacy V8/V9 meters may remain temporarily unbound (feature_id NULL) because UsageCore
-- cannot safely invent a governing Feature. New Phase 6C creates must supply feature_id.
-- quota_state is the synchronous admission counter; usage_window_aggregate remains async reporting.
-- No commercial-period finalization, adjustments, or billing in this migration.

ALTER TABLE meter_definition
    ADD COLUMN feature_id UUID REFERENCES feature (id);

CREATE INDEX idx_meter_definition_feature_id ON meter_definition (feature_id);

-- Enforce feature binding for new/updated rows without rejecting legacy unbound meters.
-- NOT VALID skips existing rows; PostgreSQL still checks the constraint on INSERT/UPDATE.
ALTER TABLE meter_definition
    ADD CONSTRAINT ck_meter_definition_feature_id_required
        CHECK (feature_id IS NOT NULL) NOT VALID;

CREATE TABLE quota_state (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    meter_definition_id   UUID         NOT NULL REFERENCES meter_definition (id),
    window_start          TIMESTAMPTZ  NOT NULL,
    window_end            TIMESTAMPTZ  NOT NULL,
    configured_limit      BIGINT       NOT NULL,
    consumed_quantity     BIGINT       NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_quota_state_tenant_meter_window
        UNIQUE (tenant_id, meter_definition_id, window_start, window_end),
    CONSTRAINT ck_quota_state_limit_positive CHECK (configured_limit > 0),
    CONSTRAINT ck_quota_state_consumed_non_negative CHECK (consumed_quantity >= 0),
    CONSTRAINT ck_quota_state_consumed_within_limit CHECK (consumed_quantity <= configured_limit),
    CONSTRAINT ck_quota_state_window CHECK (window_end > window_start)
);

CREATE INDEX idx_quota_state_tenant_meter
    ON quota_state (tenant_id, meter_definition_id);

CREATE TABLE quota_consumption (
    id                        UUID PRIMARY KEY,
    event_id                  UUID,
    tenant_id                 UUID         NOT NULL,
    principal_id              VARCHAR(255) NOT NULL,
    product_key               VARCHAR(64)  NOT NULL,
    meter_key                 VARCHAR(64)  NOT NULL,
    meter_definition_id       UUID         REFERENCES meter_definition (id),
    feature_key               VARCHAR(64)  NOT NULL,
    quantity                  BIGINT       NOT NULL,
    contribution              BIGINT       NOT NULL,
    occurred_at               TIMESTAMPTZ  NOT NULL,
    window_start              TIMESTAMPTZ,
    window_end                TIMESTAMPTZ,
    idempotency_key           VARCHAR(128) NOT NULL,
    correlation_id            VARCHAR(128),
    decision                  VARCHAR(32)  NOT NULL,
    reason                    VARCHAR(64)  NOT NULL,
    configured_limit          BIGINT,
    consumed_after            BIGINT,
    remaining_after           BIGINT,
    contract_version_id       UUID,
    contract_version_number   INTEGER,
    decided_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_quota_consumption_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_quota_consumption_event_id UNIQUE (event_id),
    CONSTRAINT ck_quota_consumption_quantity CHECK (quantity > 0),
    CONSTRAINT ck_quota_consumption_contribution CHECK (contribution >= 0),
    CONSTRAINT ck_quota_consumption_decision CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_quota_consumption_accepted_event CHECK (
        (decision = 'ACCEPTED' AND event_id IS NOT NULL)
        OR (decision = 'REJECTED')
    ),
    CONSTRAINT ck_quota_consumption_window CHECK (
        (window_start IS NULL AND window_end IS NULL)
        OR (window_start IS NOT NULL AND window_end IS NOT NULL AND window_end > window_start)
    )
);

CREATE INDEX idx_quota_consumption_tenant_meter
    ON quota_consumption (tenant_id, meter_definition_id);
