-- Phase 6A: MeterDefinition (catalogue configuration) + derived usage_aggregate.
-- usage_ledger remains canonical raw history; usage_aggregate is rebuildable derived state.
-- No billing periods, windows, quota, or pricing in this migration.

CREATE TABLE meter_definition (
    id                  UUID PRIMARY KEY,
    product_id          UUID         NOT NULL REFERENCES product (id),
    meter_key           VARCHAR(64)  NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    aggregation_type    VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_meter_definition_product_meter_key UNIQUE (product_id, meter_key),
    CONSTRAINT ck_meter_definition_aggregation_type
        CHECK (aggregation_type IN ('SUM', 'COUNT', 'MAX')),
    CONSTRAINT ck_meter_definition_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_meter_definition_display_name_nonblank
        CHECK (btrim(display_name) <> '')
);

CREATE INDEX idx_meter_definition_product_id ON meter_definition (product_id);

CREATE TABLE usage_aggregate (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    product_id            UUID         NOT NULL REFERENCES product (id),
    meter_definition_id   UUID         NOT NULL REFERENCES meter_definition (id),
    meter_key             VARCHAR(64)  NOT NULL,
    aggregation_type      VARCHAR(32)  NOT NULL,
    aggregate_value       BIGINT       NOT NULL,
    event_count           BIGINT       NOT NULL,
    last_event_at         TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_usage_aggregate_tenant_meter UNIQUE (tenant_id, meter_definition_id),
    CONSTRAINT ck_usage_aggregate_aggregation_type
        CHECK (aggregation_type IN ('SUM', 'COUNT', 'MAX')),
    CONSTRAINT ck_usage_aggregate_event_count CHECK (event_count >= 0),
    CONSTRAINT ck_usage_aggregate_value_non_negative CHECK (aggregate_value >= 0)
);

CREATE INDEX idx_usage_aggregate_tenant_product
    ON usage_aggregate (tenant_id, product_id);
