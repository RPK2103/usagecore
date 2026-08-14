-- Phase 8B: explicit UsageAdjustment for quarantined canonical usage.
-- Derived commercial aggregates may change only through this append-oriented evidence.
-- Canonical usage_ledger and commercial_usage_exception rows are never rewritten.
-- Completed reconciliation_run / reconciliation_item rows remain immutable.

CREATE TABLE usage_adjustment (
    id                              UUID PRIMARY KEY,
    tenant_id                       UUID         NOT NULL REFERENCES tenant (id),
    product_id                      UUID         NOT NULL REFERENCES product (id),
    meter_definition_id             UUID         NOT NULL REFERENCES meter_definition (id),
    commercial_period_id            UUID         NOT NULL REFERENCES commercial_period (id),
    commercial_usage_exception_id   UUID         NOT NULL REFERENCES commercial_usage_exception (id),
    source_event_id                 UUID         NOT NULL REFERENCES usage_ledger (event_id),
    reconciliation_run_id           UUID         NOT NULL REFERENCES reconciliation_run (id),
    adjustment_type                 VARCHAR(64)  NOT NULL,
    aggregation_type                VARCHAR(16)  NOT NULL,
    quantity                        BIGINT       NOT NULL,
    aggregate_value_contribution    BIGINT       NOT NULL,
    event_count_contribution        BIGINT       NOT NULL,
    window_start                    TIMESTAMPTZ  NOT NULL,
    window_end                      TIMESTAMPTZ  NOT NULL,
    idempotency_key                 VARCHAR(128) NOT NULL,
    reason                          VARCHAR(512) NOT NULL,
    applied_at                      TIMESTAMPTZ  NOT NULL,
    applied_by                      VARCHAR(255) NOT NULL,
    correlation_id                  VARCHAR(128),
    CONSTRAINT uq_usage_adjustment_exception UNIQUE (commercial_usage_exception_id),
    CONSTRAINT uq_usage_adjustment_source_event UNIQUE (source_event_id),
    CONSTRAINT uq_usage_adjustment_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_usage_adjustment_type CHECK (
        adjustment_type = 'APPLY_QUARANTINED_USAGE'
    ),
    CONSTRAINT ck_usage_adjustment_agg_type CHECK (
        aggregation_type IN ('SUM', 'COUNT', 'MAX')
    ),
    CONSTRAINT ck_usage_adjustment_window CHECK (window_start < window_end),
    CONSTRAINT ck_usage_adjustment_quantity CHECK (quantity > 0),
    CONSTRAINT ck_usage_adjustment_event_count CHECK (event_count_contribution >= 0),
    CONSTRAINT ck_usage_adjustment_agg_value CHECK (aggregate_value_contribution >= 0),
    CONSTRAINT ck_usage_adjustment_reason CHECK (btrim(reason) <> '')
);

CREATE INDEX idx_usage_adjustment_tenant_period
    ON usage_adjustment (tenant_id, commercial_period_id);

CREATE INDEX idx_usage_adjustment_run
    ON usage_adjustment (reconciliation_run_id);

-- Existing Phase 8A item rows backfill to 0 (no UsageAdjustment existed).
ALTER TABLE reconciliation_item
    ADD COLUMN adjusted_event_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE reconciliation_item
    ADD COLUMN unresolved_exception_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE reconciliation_item
    ADD CONSTRAINT ck_reconciliation_item_adjusted_count CHECK (adjusted_event_count >= 0);

ALTER TABLE reconciliation_item
    ADD CONSTRAINT ck_reconciliation_item_unresolved_count CHECK (unresolved_exception_count >= 0);
