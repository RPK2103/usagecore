-- Phase 6B: event-time windowed metering + late-event classification evidence.
-- usage_aggregate remains lifetime derived state; usage_window_aggregate is temporal.
-- Windows are UTC calendar intervals [window_start, window_end). No Streams, quota, or billing.

ALTER TABLE meter_definition
    ADD COLUMN aggregation_window VARCHAR(32) NOT NULL DEFAULT 'MONTHLY';

ALTER TABLE meter_definition
    ADD CONSTRAINT ck_meter_definition_aggregation_window
        CHECK (aggregation_window IN ('DAILY', 'MONTHLY'));

-- Explicit configuration required for new rows; DEFAULT only migrates existing V8 meters.
ALTER TABLE meter_definition
    ALTER COLUMN aggregation_window DROP DEFAULT;

ALTER TABLE usage_ledger
    ADD COLUMN is_late BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE usage_window_aggregate (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    product_id            UUID         NOT NULL REFERENCES product (id),
    meter_definition_id   UUID         NOT NULL REFERENCES meter_definition (id),
    meter_key             VARCHAR(64)  NOT NULL,
    aggregation_type      VARCHAR(32)  NOT NULL,
    window_start          TIMESTAMPTZ  NOT NULL,
    window_end            TIMESTAMPTZ  NOT NULL,
    aggregate_value       BIGINT       NOT NULL,
    event_count           BIGINT       NOT NULL,
    first_event_at        TIMESTAMPTZ,
    last_event_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_usage_window_aggregate_tenant_meter_window
        UNIQUE (tenant_id, meter_definition_id, window_start, window_end),
    CONSTRAINT ck_usage_window_aggregate_aggregation_type
        CHECK (aggregation_type IN ('SUM', 'COUNT', 'MAX')),
    CONSTRAINT ck_usage_window_aggregate_window_order
        CHECK (window_start < window_end),
    CONSTRAINT ck_usage_window_aggregate_value_non_negative
        CHECK (aggregate_value >= 0),
    CONSTRAINT ck_usage_window_aggregate_event_count
        CHECK (event_count >= 0)
);

CREATE INDEX idx_usage_window_aggregate_tenant_product_meter
    ON usage_window_aggregate (tenant_id, product_id, meter_key, window_start);
