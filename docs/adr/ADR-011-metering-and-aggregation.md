# ADR-011: Metering and deterministic usage aggregation

## Status

Accepted

## Context

Phase 5B established idempotent Kafka consumption: `processed_event` inbox +
canonical `usage_ledger` in one PostgreSQL transaction. Delivery remains
**at-least-once**. Phase 6A adds the first deterministic metering foundation:

```
usage_ledger  →  MeterDefinition  →  aggregation strategy  →  usage_aggregate
```

Requirements:

- Support only `SUM`, `COUNT`, and `MAX`
- Aggregate updates must be concurrency-safe under concurrent consumers
- Duplicate Kafka redelivery must not apply the aggregate twice
- Meter configuration is commercial catalogue state (Control Plane ownership)
- Usage Pipeline must not depend on the Control Plane application at compile time

## Decision

### MeterDefinition ownership

`MeterDefinition` is Control Plane catalogue configuration, scoped to a Product:

- Unique `(product_id, meter_key)`
- Aggregation type is authoritative configuration — never taken from the usage event
- Minimal lifecycle: `ACTIVE` / `INACTIVE`
- Administrative API: `POST/GET /api/v1/products/{productId}/meters`

### SUM / COUNT / MAX semantics

| Type | Contribution per event |
| --- | --- |
| `SUM` | `+ quantity` |
| `COUNT` | `+ 1` (quantity ignored) |
| `MAX` | `GREATEST(existing, quantity)` |

### Canonical ledger vs derived aggregate

| Store | Role |
| --- | --- |
| `usage_ledger` | Canonical, append-only, replayable raw usage history |
| `usage_aggregate` | Derived, rebuildable total state per tenant + meter |

Never mutate or delete ledger rows as part of aggregation. Never treat the
aggregate as the sole source of truth.

### Atomic consumer + aggregation transaction

For a new `UsageReceived` event:

```
BEGIN
  claim processed_event
  if duplicate → successful no-op
  else
    insert usage_ledger
    resolve ACTIVE MeterDefinition (productKey + meterKey)
    atomic UPSERT usage_aggregate
COMMIT
```

Inbox claim, ledger insert, and aggregate update succeed or fail together.

### PostgreSQL atomic UPSERT strategy

Do **not** read-modify-write aggregate values in Java. Concurrent updates use:

```sql
INSERT … VALUES (contribution, event_count=1, …)
ON CONFLICT (tenant_id, meter_definition_id)
DO UPDATE SET
  aggregate_value = CASE
    WHEN MAX THEN GREATEST(existing, excluded)
    ELSE existing + excluded   -- SUM and COUNT
  END,
  event_count = event_count + 1,
  last_event_at = GREATEST(existing, excluded)
```

Contribution is `quantity` for SUM/MAX and `1` for COUNT.

### Tenant-scoped aggregate identity

`UNIQUE (tenant_id, meter_definition_id)` — Acme and Globex never share a row
even for the same product/meter keys.

### eventId duplicate protection

Duplicate protection remains the Phase 5B inbox claim. After a successful commit,
redelivery finds `processed_event` already claimed and returns without touching
the aggregate.

### Unknown / inactive meter

Missing or inactive meters raise `UnknownUsageMeterException` (non-retryable).
No inbox/ledger/aggregate commit. Kafka routes to DLQ via existing poison handling.
Meters are never auto-created and never defaulted to SUM.

### Out-of-order event time

Phase 6A does not implement windows or late-event classification.
If `last_event_at` is stored, it means **maximum `occurredAt` seen**, updated with
`GREATEST` so arrival order cannot move it backwards.

### Why Kafka Streams is deferred

Kafka Streams is **intentionally deferred** until event-time windows, state-store
semantics, late events, or topology-level aggregation provide a concrete benefit
beyond the current PostgreSQL-backed deterministic aggregation model.

Phase 6A proves correctness first with transactional PostgreSQL aggregation
inside the existing consumer boundary.

### Aggregate read API

`GET /api/v1/usage/aggregates/{productKey}/{meterKey}` on Usage Pipeline returns
Phase 6A derived state only. Tenant identity comes from JWT — never from the
request. No quota/billing/period fields.

## Consequences

### Positive

- Deterministic, testable aggregation with real PostgreSQL concurrency evidence
- Independent deployability preserved (JDBC read of shared schema)
- Duplicate redelivery remains a successful no-op including aggregates

### Negative / limitations

- No billing periods, windows, watermarks, or late-event adjustments (Phase 6B+)
- No DISTINCT_COUNT, pricing, quota, or remaining-quota
- `BIGINT` aggregate totals can overflow at extreme volumes; PostgreSQL arithmetic
  is used for concurrency — rebuild from ledger if needed
- Aggregate is eventually rebuildable but rebuild tooling is not in Phase 6A

### Non-claims

This ADR does **not** claim exactly-once transport, billing correctness, quota
correctness, late-event correctness, Kafka Streams processing, or production
readiness.
