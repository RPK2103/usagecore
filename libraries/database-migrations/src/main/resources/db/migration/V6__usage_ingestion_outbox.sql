-- Phase 5A: durable usage ingestion + transactional outbox.
-- HTTP acceptance is committed to PostgreSQL; Kafka publication is asynchronous.
-- Delivery remains at-least-once — consumer inbox/dedup is Phase 5B.

CREATE TABLE usage_ingestion (
    id               UUID PRIMARY KEY,
    event_id         UUID         NOT NULL,
    tenant_id        UUID         NOT NULL,
    principal_id     VARCHAR(255) NOT NULL,
    product_key      VARCHAR(64)  NOT NULL,
    meter_key        VARCHAR(64)  NOT NULL,
    quantity         BIGINT       NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    correlation_id   VARCHAR(128),
    accepted_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_usage_ingestion_event_id UNIQUE (event_id),
    CONSTRAINT uq_usage_ingestion_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_usage_ingestion_quantity CHECK (quantity > 0)
);

CREATE TABLE outbox_event (
    id                   UUID PRIMARY KEY,
    event_id             UUID         NOT NULL,
    event_type           VARCHAR(128) NOT NULL,
    event_version        VARCHAR(32)  NOT NULL,
    topic                VARCHAR(255) NOT NULL,
    partition_key        VARCHAR(512) NOT NULL,
    serialized_envelope  TEXT         NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    published_at         TIMESTAMPTZ,
    CONSTRAINT uq_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED'))
);

-- Publisher polls PENDING rows ordered by creation time (FOR UPDATE SKIP LOCKED).
CREATE INDEX idx_outbox_event_pending_created
    ON outbox_event (created_at)
    WHERE status = 'PENDING';
