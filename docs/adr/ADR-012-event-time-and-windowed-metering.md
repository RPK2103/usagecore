# ADR-012: Event-time windows and late-event semantics

## Status

Accepted

## Context

Phase 6A established lifetime `usage_aggregate` updates inside the idempotent consumer
transaction. Phase 6B must answer:

- Which calendar window owns an event?
- What happens when an old event arrives late?
- Can out-of-order arrivals update historical windows safely?
- Can windowed totals be rebuilt from `usage_ledger`?
- What constitutes late vs accepted usage?

Billing-period finalization, quota, and adjustments remain out of scope.

## Decision

### Event time vs processing time

| Concept | Source |
| --- | --- |
| Event time | `UsageReceived.occurredAt` (business occurrence) |
| Processing time | Injected `java.time.Clock` instant when the consumer accepts the event (`recorded_at` / `processed_at`) |

Window assignment uses **event time only**. Processing time classifies lateness and
is never used to choose the window.

### UTC windows (v1)

Metering windows are **UTC calendar intervals**. Tenant-local commercial timezones
are explicitly deferred. Machine-local timezone must not be used.

### Half-open intervals

Windows are `[window_start, window_end)` — consistent with ADR-005.

Example MONTHLY UTC for `2026-08-31T23:59:59Z`:

```
[2026-08-01T00:00:00Z, 2026-09-01T00:00:00Z)
```

`2026-09-01T00:00:00Z` belongs to September, not August.

### Supported window types

`MeterDefinition.aggregationWindow`:

- `MONTHLY` (primary v1 path)
- `DAILY` (same resolver model; available when configured)

No cron expressions, sliding/session windows, fiscal calendars, or per-meter timezones.

### Late-event definition and behavior

An event is **late** iff processing time is at or after its event-time window end:

```
processedAt >= window.end
```

Late does **not** mean rejected in Phase 6B:

- accepted
- ledgered normally
- updates the historical event-time window aggregate
- recorded as `usage_ledger.is_late = true`

Commercial immutability arrives later when periods can become FINALIZED (Phase 7).

### Out-of-order behavior

Arrival order is irrelevant. Multiple `occurredAt` values in the same calendar window
update that same window row. Chronological vs shuffled processing must yield the same
window total.

### Canonical ledger vs temporal aggregates

| Store | Role |
| --- | --- |
| `usage_ledger` | Canonical append-only history (`occurred_at`, quantity, keys, `is_late`) |
| `usage_aggregate` | Lifetime derived total (Phase 6A) |
| `usage_window_aggregate` | Event-time window derived total (Phase 6B) |

Window identity:

```
UNIQUE (tenant_id, meter_definition_id, window_start, window_end)
```

### MeterDefinition historical-semantics protection

After creation, `meterKey`, `aggregationType`, and `aggregationWindow` are immutable
in domain/application (final fields; persistence updates only display name / status).
No MeterDefinition versioning table in Phase 6B.

Trade-off: DB triggers are not added solely for this; application policy + lack of
update API are the enforcement. Direct SQL mutation remains an operational risk.

Given immutability, ledger rows retain enough information (`tenant`, product/meter keys,
`quantity`, `occurred_at`, `event_id`) to rebuild window aggregates for the meter
configuration that existed at processing time. Full replay tooling is deferred.

### PostgreSQL window UPSERT (not Kafka Streams)

Consumer transaction:

```
BEGIN
  claim processed_event
  if duplicate → successful no-op
  resolve ACTIVE MeterDefinition
  resolve window from occurredAt + aggregationWindow
  classify late via Clock
  insert usage_ledger (with is_late)
  UPSERT usage_aggregate (lifetime)
  UPSERT usage_window_aggregate (event-time window)
COMMIT
```

Concurrency uses PostgreSQL `ON CONFLICT` arithmetic (`SUM`/`COUNT` add; `MAX` uses
`GREATEST`) — never Java read-modify-write.

### Kafka Streams evaluation

Evaluated against Phase 6B needs (event-time windows, late arrivals, stateful
aggregation, recovery, rebuildability, operational complexity).

**Decision: defer Kafka Streams.** PostgreSQL remains the aggregate store because:

- same transaction as inbox + ledger
- strong uniqueness / check constraints
- simple Testcontainers evidence
- rebuildable derived state from ledger

Streams would add state stores, changelog topics, restore semantics, repartitioning,
EOS complexity, and a separate stream/DB consistency problem without a concrete
correctness benefit for the current scale and requirements.

## Consequences

### Positive

- Deterministic event-time metering with explicit late classification
- Out-of-order and late arrivals update historical windows before commercial finalization
- Lifetime + window aggregates remain atomically tied to inbox/ledger

### Negative / limitations

- No OPEN/CLOSING/FINALIZED commercial periods
- No rejection of late events; no `UsageAdjustment`
- No tenant timezone; UTC only
- No rebuild/replay tooling yet
- BIGINT overflow risk same as Phase 6A
- Semantic immutability is application-enforced, not trigger-enforced

### Non-claims

This ADR does **not** claim commercial-period correctness, finalized-period correctness,
quota correctness, billing correctness, exactly-once transport, Kafka Streams
processing, or production readiness.
