-- Phase 5B: consumer inbox (processed_event) + canonical immutable usage ledger.
-- Kafka delivery remains at-least-once; event_id uniqueness makes redelivery safe.
-- No aggregates, quota, or billing tables in this migration.

CREATE TABLE processed_event (
    event_id         UUID PRIMARY KEY,
    event_type       VARCHAR(128) NOT NULL,
    event_version    VARCHAR(32)  NOT NULL,
    tenant_id        UUID         NOT NULL,
    consumer_name    VARCHAR(128) NOT NULL,
    processed_at     TIMESTAMPTZ  NOT NULL,
    correlation_id   VARCHAR(128)
);

CREATE TABLE usage_ledger (
    id               UUID PRIMARY KEY,
    event_id         UUID         NOT NULL,
    tenant_id        UUID         NOT NULL,
    product_key      VARCHAR(64)  NOT NULL,
    meter_key        VARCHAR(64)  NOT NULL,
    quantity         BIGINT       NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    correlation_id   VARCHAR(128),
    principal_id     VARCHAR(255),
    recorded_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_usage_ledger_event_id UNIQUE (event_id),
    CONSTRAINT ck_usage_ledger_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_usage_ledger_tenant_occurred
    ON usage_ledger (tenant_id, occurred_at);
