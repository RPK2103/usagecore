-- Phase 8A: deterministic reconciliation runs (read / rebuild / compare / report).
-- Reconciliation never repairs derived commercial state. Corrections belong to Phase 8B.
-- Completed runs are immutable historical evidence; reruns create new run rows.

CREATE TABLE reconciliation_run (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID         NOT NULL REFERENCES tenant (id),
    product_id                  UUID         NOT NULL REFERENCES product (id),
    commercial_period_id        UUID         NOT NULL REFERENCES commercial_period (id),
    status                      VARCHAR(32)  NOT NULL,
    result                      VARCHAR(32),
    started_at                  TIMESTAMPTZ  NOT NULL,
    completed_at                TIMESTAMPTZ,
    started_by                  VARCHAR(255) NOT NULL,
    canonical_event_count       BIGINT,
    quarantined_event_count     BIGINT,
    matched_meter_count         INT,
    mismatched_meter_count      INT,
    correlation_id              VARCHAR(128),
    failure_reason              VARCHAR(512),
    CONSTRAINT ck_reconciliation_run_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_reconciliation_run_result CHECK (
        result IS NULL OR result IN ('MATCH', 'MISMATCH')
    ),
    CONSTRAINT ck_reconciliation_run_completed CHECK (
        (status = 'RUNNING' AND completed_at IS NULL AND result IS NULL
            AND matched_meter_count IS NULL AND mismatched_meter_count IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND result IS NOT NULL
            AND canonical_event_count IS NOT NULL AND quarantined_event_count IS NOT NULL
            AND matched_meter_count IS NOT NULL AND mismatched_meter_count IS NOT NULL
            AND failure_reason IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND result IS NULL
            AND failure_reason IS NOT NULL)
    )
);

-- At most one active RUNNING reconciliation per commercial period.
CREATE UNIQUE INDEX uq_reconciliation_run_period_running
    ON reconciliation_run (commercial_period_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_reconciliation_run_tenant_period
    ON reconciliation_run (tenant_id, commercial_period_id, started_at DESC);

CREATE INDEX idx_reconciliation_run_period
    ON reconciliation_run (commercial_period_id, started_at DESC);

CREATE TABLE reconciliation_item (
    id                          UUID PRIMARY KEY,
    reconciliation_run_id       UUID         NOT NULL REFERENCES reconciliation_run (id),
    meter_definition_id         UUID         NOT NULL REFERENCES meter_definition (id),
    meter_key                   VARCHAR(128) NOT NULL,
    aggregation_type            VARCHAR(16)  NOT NULL,
    window_start                TIMESTAMPTZ  NOT NULL,
    window_end                  TIMESTAMPTZ  NOT NULL,
    observed_expected_value     BIGINT       NOT NULL,
    commercial_expected_value   BIGINT       NOT NULL,
    actual_value                BIGINT,
    difference                  BIGINT,
    expected_event_count        BIGINT       NOT NULL,
    actual_event_count          BIGINT,
    quarantined_event_count     BIGINT       NOT NULL,
    observed_event_count        BIGINT       NOT NULL,
    quota_consumed_value        BIGINT,
    status                      VARCHAR(16)  NOT NULL,
    classification              VARCHAR(64)  NOT NULL,
    CONSTRAINT ck_reconciliation_item_agg_type CHECK (
        aggregation_type IN ('SUM', 'COUNT', 'MAX')
    ),
    CONSTRAINT ck_reconciliation_item_status CHECK (
        status IN ('MATCH', 'MISMATCH')
    ),
    CONSTRAINT ck_reconciliation_item_classification CHECK (
        classification IN (
            'MATCH',
            'AGGREGATE_VALUE_MISMATCH',
            'EVENT_COUNT_MISMATCH',
            'MISSING_AGGREGATE',
            'UNEXPECTED_AGGREGATE',
            'QUOTA_REPORTING_DIVERGENCE'
        )
    ),
    CONSTRAINT ck_reconciliation_item_window CHECK (window_start < window_end)
);

CREATE INDEX idx_reconciliation_item_run
    ON reconciliation_item (reconciliation_run_id);

CREATE INDEX idx_reconciliation_item_run_meter
    ON reconciliation_item (reconciliation_run_id, meter_definition_id);
