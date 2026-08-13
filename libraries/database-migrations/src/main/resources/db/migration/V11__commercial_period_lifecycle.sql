-- Phase 7: commercial period lifecycle + blocked-usage evidence.
-- CommercialPeriod is separate from UsageWindow (event-time buckets).
-- FINALIZED is terminal; ordinary processing must not mutate finalized commercial aggregates.
-- Manual administrative finalization does NOT prove reconciliation correctness (Phase 8).
-- btree_gist already enabled in V3 for interval exclusion constraints.

CREATE TABLE commercial_period (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID         NOT NULL REFERENCES tenant (id),
    product_id              UUID         NOT NULL REFERENCES product (id),
    period_start            TIMESTAMPTZ  NOT NULL,
    period_end              TIMESTAMPTZ  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    closing_started_at      TIMESTAMPTZ,
    reconciling_started_at  TIMESTAMPTZ,
    finalized_at            TIMESTAMPTZ,
    finalized_by            VARCHAR(255),
    CONSTRAINT uq_commercial_period_tenant_product_bounds
        UNIQUE (tenant_id, product_id, period_start, period_end),
    CONSTRAINT ck_commercial_period_bounds CHECK (period_start < period_end),
    CONSTRAINT ck_commercial_period_status CHECK (
        status IN ('OPEN', 'CLOSING', 'RECONCILING', 'FINALIZED')
    ),
    CONSTRAINT ck_commercial_period_closing_ts CHECK (
        (status = 'OPEN' AND closing_started_at IS NULL
            AND reconciling_started_at IS NULL AND finalized_at IS NULL AND finalized_by IS NULL)
        OR (status = 'CLOSING' AND closing_started_at IS NOT NULL
            AND reconciling_started_at IS NULL AND finalized_at IS NULL AND finalized_by IS NULL)
        OR (status = 'RECONCILING' AND closing_started_at IS NOT NULL
            AND reconciling_started_at IS NOT NULL AND finalized_at IS NULL AND finalized_by IS NULL)
        OR (status = 'FINALIZED' AND closing_started_at IS NOT NULL
            AND reconciling_started_at IS NOT NULL AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL)
    ),
    CONSTRAINT ex_commercial_period_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        product_id WITH =,
        tstzrange(period_start, period_end, '[)') WITH &&
    )
);

CREATE INDEX idx_commercial_period_tenant_product_range
    ON commercial_period (tenant_id, product_id, period_start, period_end);

CREATE INDEX idx_commercial_period_status
    ON commercial_period (status);

-- Append-only lifecycle evidence (who transitioned what, when).
CREATE TABLE commercial_period_transition (
    id                      UUID PRIMARY KEY,
    commercial_period_id    UUID         NOT NULL REFERENCES commercial_period (id),
    from_status             VARCHAR(32)  NOT NULL,
    to_status               VARCHAR(32)  NOT NULL,
    principal_id            VARCHAR(255) NOT NULL,
    occurred_at             TIMESTAMPTZ  NOT NULL,
    correlation_id          VARCHAR(128),
    CONSTRAINT ck_commercial_period_transition_from CHECK (
        from_status IN ('OPEN', 'CLOSING', 'RECONCILING', 'FINALIZED')
    ),
    CONSTRAINT ck_commercial_period_transition_to CHECK (
        to_status IN ('OPEN', 'CLOSING', 'RECONCILING', 'FINALIZED')
    )
);

CREATE INDEX idx_commercial_period_transition_period
    ON commercial_period_transition (commercial_period_id, occurred_at);

-- Quarantine / evidence for usage that cannot mutate commercial aggregates
-- because the covering period is RECONCILING or FINALIZED. Not UsageAdjustment.
CREATE TABLE commercial_usage_exception (
    id                      UUID PRIMARY KEY,
    event_id                UUID         NOT NULL,
    tenant_id               UUID         NOT NULL,
    product_id              UUID         NOT NULL,
    meter_definition_id     UUID         NOT NULL REFERENCES meter_definition (id),
    commercial_period_id    UUID         NOT NULL REFERENCES commercial_period (id),
    reason                  VARCHAR(64)  NOT NULL,
    occurred_at             TIMESTAMPTZ  NOT NULL,
    recorded_at             TIMESTAMPTZ  NOT NULL,
    correlation_id          VARCHAR(128),
    CONSTRAINT uq_commercial_usage_exception_event_id UNIQUE (event_id),
    CONSTRAINT ck_commercial_usage_exception_reason CHECK (
        reason IN ('PERIOD_RECONCILING', 'PERIOD_FINALIZED')
    )
);

CREATE INDEX idx_commercial_usage_exception_period
    ON commercial_usage_exception (commercial_period_id);

CREATE INDEX idx_commercial_usage_exception_tenant
    ON commercial_usage_exception (tenant_id, product_id);
