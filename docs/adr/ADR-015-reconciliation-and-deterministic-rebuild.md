# ADR-015: Reconciliation and deterministic rebuild

## Status

Accepted (Phase 8A)

## Context

Phase 7 freezes ordinary commercial aggregate mutation for `RECONCILING` / `FINALIZED` periods and quarantines blocked usage into `commercial_usage_exception`. Manual finalization does **not** prove that derived reporting state matches canonical evidence.

UsageCore needs an explicit answer to:

> If we reconstruct expected commercial usage from canonical evidence, does it match currently persisted derived state?

Phase 8A must never silently repair derived or finalized commercial state. Corrections belong to Phase 8B (`UsageAdjustment` — [ADR-016](ADR-016-explicit-usage-adjustments.md)).

## Decision

### Ownership

Reconciliation lives in **Usage Pipeline** because canonical (`usage_ledger`), derived (`usage_window_aggregate` / `usage_aggregate`), admission (`quota_state`), and quarantine (`commercial_usage_exception`) tables are processed there.

Control Plane continues to own commercial period lifecycle transitions. It probes shared PostgreSQL for an active `reconciliation_run` before `FINALIZED` — no HTTP coupling between apps.

### Canonical vs derived vs admission

| Class | Tables | Role in Phase 8A |
| --- | --- | --- |
| Canonical evidence | `usage_ledger` (+ quarantine rows) | Rebuild input |
| Derived reporting | `usage_window_aggregate`, `usage_aggregate` | Comparison target only |
| Admission | `quota_state`, `quota_consumption` | Optional divergence visibility |
| Lifecycle | `commercial_period` | Scope + allow/deny reconciliation |

Rebuild **must not** use current aggregates or quota as inputs to expected totals.

### Read / rebuild / compare / report

Phase 8A is dry-run evidence only:

1. Create `reconciliation_run` (`RUNNING`)
2. Select period-scoped ledger events (`occurred_at ∈ [period_start, period_end)`)
3. Exclude quarantined `event_id`s from **commercial** expected totals
4. Rebuild expected window aggregates with live meter semantics (`SUM` / `COUNT` / `MAX`)
5. Compare to persisted `usage_window_aggregate`
6. Optionally compare `quota_state.consumed` vs commercial expected for SUM/COUNT
7. Persist immutable `reconciliation_item` rows and complete the run

Production reconciliation code does not `UPDATE`/`DELETE` aggregates or quota.

### Allowed commercial-period states

| Status | Reconciliation |
| --- | --- |
| `OPEN` | Rejected (409) |
| `CLOSING` | Rejected (409) |
| `RECONCILING` | Allowed |
| `FINALIZED` | Allowed, strictly read-only report |

MATCH does **not** auto-finalize. Finalization remains an explicit Control Plane admin action.

### Rebuild semantics

Reuse consumer semantics:

- **SUM** → `sum(quantity)` of commercially applicable events
- **COUNT** → `count(events)` (quantity ignored)
- **MAX** → `max(quantity)`
- Empty commercial set → expected `0` (no fabricated monetary impact)

Window assignment uses `UsageWindowResolver` on `occurredAt` (UTC half-open), not processing time.

### Observed vs commercial expected

Quarantined events remain visible:

- `observedExpected*` includes all canonical ledger events in scope
- `commercialExpected*` excludes `commercial_usage_exception` event ids for the period
- Quarantine counts are reported; they are not automatically classified as corruption

### Mismatch classifications

Small taxonomy:

- `MATCH`
- `AGGREGATE_VALUE_MISMATCH` (`difference = actual - commercialExpected`)
- `EVENT_COUNT_MISMATCH`
- `MISSING_AGGREGATE`
- `UNEXPECTED_AGGREGATE`
- `QUOTA_REPORTING_DIVERGENCE`

Missing rows are not silently treated as zero matches when commercial evidence predicts a non-empty aggregate.

### Determinism assumptions

Given the same canonical inputs, meter semantics, and period bounds, repeated runs produce the same business comparison results (new run ids/timestamps differ).

Historical rebuild joins current `meter_definition` for `aggregation_type` / `aggregation_window`. This is deterministic **only while semantic meter configuration remains immutable** (Phase 6B). MeterDefinition versioning is not introduced in 8A.

No wall-clock randomness and no mutable plan/contract reinterpretation are used to rebuild historical aggregates.

### Transaction model

1. TX1 `REQUIRES_NEW`: insert `RUNNING` (partial unique index enforces one active run per period)
2. TX2: `FOR SHARE` period row, rebuild, insert items, mark `COMPLETED`
3. On failure: TX3 `REQUIRES_NEW`: mark `FAILED` with sanitized bounded `failure_reason` (no stack traces/secrets)

No partially `COMPLETED` report: items are written only in the successful completion path.

### Isolation reasoning

Default PostgreSQL / Spring isolation remains **READ COMMITTED**. This is sufficient for Phase 8A because:

- `RECONCILING` / `FINALIZED` already block normal aggregate mutation
- Reconciliation itself never mutates derived commercial state
- Period `FOR SHARE` on run start serializes with lifecycle `UPDATE`
- FINALIZED conditional `UPDATE` includes `NOT EXISTS` of `RUNNING` rows
- Active `RUNNING` uniqueness is a partial unique index

We do not claim snapshot isolation or upgrade global isolation.

### Concurrent runs

Partial unique index:

```sql
UNIQUE (commercial_period_id) WHERE status = 'RUNNING'
```

Completed historical runs may coexist. Concurrent start → one winner; loser gets deterministic conflict.

### Finalization interaction

`RECONCILING → FINALIZED` is rejected while any `reconciliation_run` for that period is `RUNNING`.

Enforcement is PostgreSQL-authoritative, not a Java-only pre-check:

- Starting a run takes `SELECT … FOR SHARE` on `commercial_period` in the same transaction as inserting `RUNNING`, so it serializes with the lifecycle `UPDATE`.
- The FINALIZED conditional `UPDATE` includes `AND NOT EXISTS (… status = 'RUNNING')`.

Stale `RUNNING` rows (process crash after TX1) require operational cleanup in v1 — no leasing/timeout built yet.

### Rebuild vs Kafka replay

Phase 8A is **deterministic logical rebuild from `usage_ledger`**. It is not Kafka historical replay and does not republish events to Kafka.

### API / security

Usage Pipeline:

- `POST /api/v1/reconciliation/periods/{commercialPeriodId}/runs` — `PLATFORM_ADMIN` / `BILLING_OPERATOR`
- `GET /api/v1/reconciliation/runs/{runId}` (+ `/items`) — also `AUDITOR`

Tenant isolation: platform admin may cross tenants; tenant-bound callers must match resource tenant. No caller-controlled tenant authority override.

### Known limitations / Phase 8B handoff

Phase 8B implements explicit `UsageAdjustment` (`APPLY_QUARANTINED_USAGE`) — see [ADR-016](ADR-016-explicit-usage-adjustments.md). Still deferred:

- Compensating/undo adjustment and free-form deltas
- Automatic application of all exceptions / aggregate overwrite / quota rewrite
- Kafka operational replay
- No stale-RUNNING leasing
- Lifetime `usage_aggregate` is not the primary period comparison surface (window aggregates are)

## Consequences

Operators can prove MATCH/MISMATCH with immutable report evidence without mutating commercial state. Phase 7 lifecycle invariants remain intact. Phase 8B can consume quarantine + mismatch reports as correction inputs.
