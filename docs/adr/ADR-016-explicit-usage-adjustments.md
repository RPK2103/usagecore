# ADR-016: Explicit UsageAdjustments

## Status

Accepted (Phase 8B)

## Context

Phase 8A can prove MATCH/MISMATCH between canonical `usage_ledger` evidence and derived commercial aggregates, including quarantined late usage. It must not silently rewrite finalized or reconciling derived state.

Operators still need a first correction path when a quarantined canonical event should be commercially applied. That path must be:

- explicit
- authenticated
- append-evidenced
- derived from existing canonical usage (never caller-invented totals)

This is not Kafka historical replay, not automatic repair, and not a generic “set aggregate to X” API.

## Decision

### Ownership

Usage Pipeline owns `UsageAdjustment` because canonical ledger, quarantine, aggregates, and reconciliation evidence already live there. No new deployable service.

### Allowed operation

Phase 8B supports only:

```text
APPLY_QUARANTINED_USAGE
```

An authenticated `PLATFORM_ADMIN` or `BILLING_OPERATOR` approves applying one existing `commercial_usage_exception` whose `event_id` resolves to an existing `usage_ledger` row, referenced by a **COMPLETED** `reconciliation_run` for the same tenant and commercial period.

Callers supply only:

- `idempotencyKey`
- `reason`

Quantity, aggregation type, window, meter, and contribution are derived server-side from the ledger event + current immutable `MeterDefinition` semantics.

`AUDITOR` is read-only.

### Why finalized history is not silently editable

`FINALIZED` still forbids ordinary usage/quota mutation. Phase 8B adds an explicit exception: derived commercial aggregates may change **only** through an immutable `usage_adjustment` row with `applied_by` from the JWT subject (never a stored JWT). The commercial period is not reopened and is not moved back to `RECONCILING`.

### Canonical and exception immutability

Applying an adjustment must not insert/update/delete `usage_ledger` or delete/overwrite `commercial_usage_exception`. The exception remains proof that ordinary processing quarantined the event. The adjustment remains proof that an operator later applied it commercially.

### Contribution semantics (same as live aggregation)

| Type | `aggregate_value_contribution` | `event_count_contribution` | Aggregate apply |
| --- | --- | --- | --- |
| SUM | source `quantity` | 1 | atomic add |
| COUNT | 1 (quantity ignored) | 1 | atomic add |
| MAX | source `quantity` (GREATEST operand, not a delta) | 1 | `GREATEST(existing, quantity)` |

If existing MAX is already larger, event_count still increments and aggregate_value is unchanged.

Window assignment reuses Phase 6B `occurredAt` + `aggregationWindow` UTC half-open intervals. Request bodies cannot override windows.

### Eligibility

Allowed period states: `RECONCILING`, `FINALIZED`.

Rejected: `OPEN`, `CLOSING` (`ADJUSTMENT_NOT_ALLOWED_FOR_PERIOD`) — ordinary processing is still the correct path.

Referenced reconciliation run must be `COMPLETED`. `RUNNING` / `FAILED` references are rejected (`RECONCILIATION_RUN_NOT_COMPLETED`).

If any `reconciliation_run` for the period is `RUNNING`, adjustment is rejected (`ADJUSTMENT_BLOCKED_BY_RUNNING_RECONCILIATION`) after `SELECT … FOR UPDATE` on `commercial_period`.

### Uniqueness and idempotency

PostgreSQL constraints:

- `UNIQUE (commercial_usage_exception_id)` — one commercial application per exception
- `UNIQUE (source_event_id)` — one Phase 8B adjustment per source event
- `UNIQUE (tenant_id, idempotency_key)` — HTTP command idempotency

Identical retry (same tenant, key, run, exception, reason) returns the existing adjustment without a second aggregate effect.

Same tenant+key with a different run/exception/reason → `409 IDEMPOTENCY_CONFLICT`.

Concurrent appliers of the same exception: one insert wins; others observe uniqueness and replay or conflict. Java `synchronized` is not used.

### Transaction boundary

One PostgreSQL transaction:

1. lock commercial period `FOR UPDATE` (serializes with reconciliation `FOR SHARE`)
2. reject if a run is `RUNNING`
3. lock referenced COMPLETED run
4. lock exception `FOR UPDATE`
5. load ledger + meter
6. insert `usage_adjustment`
7. atomic UPSERT `usage_aggregate` and `usage_window_aggregate` (PostgreSQL, not Java RMW)

Failure rolls back the adjustment and both aggregate writes. No Kafka publication: the source event is already canonical; republishing `UsageReceived` would re-enter inbox/processed_event semantics.

### quota_state intentionally untouched

`quota_state` is historical synchronous admission evidence. Quarantined events may have come from `/usage/consume` (quota already incremented) or `/usage/events` (quota never consumed). Blind quota repair would rewrite admission history. Post-adjustment reconciliation may still report `QUOTA_REPORTING_DIVERGENCE`. That is explainable, not a defect of this mechanism.

### Reconciliation engine

Phase 8A excluded all quarantined event ids from commercial expected totals.

Phase 8B:

```text
commercialExpected = normally applied canonical usage
                   + quarantined events that have an APPLY_QUARANTINED_USAGE row
```

The source ledger event is counted once. Unresolved quarantine remains excluded. Old COMPLETED runs are never updated; verification is a **new** run. Adjustment success does not auto-MATCH a previous report and does not auto-finalize.

Stale reconciliation `actual_value` is not required to still equal current aggregates before apply. Unrelated adjustments may have occurred. Authority is one-adjustment-per-exception plus a subsequent verification run.

### Isolation / locking vs reconciliation

Default isolation remains **READ COMMITTED**.

- Reconciliation TX2 holds `FOR SHARE` on `commercial_period` while reading expected+actual and completing the run.
- Adjustment holds `FOR UPDATE` on the same row before mutating aggregates.

Those locks conflict, so a run cannot mix pre-adjustment expected values with post-adjustment actuals. Adjustment also refuses while a `RUNNING` row exists (partial unique index + lock). We do not claim snapshot isolation and do not raise global isolation.

### Why not Kafka replay

This is administrative correction of existing canonical evidence under PostgreSQL transaction control. Topic rewind / historical replay is a later operational concern if measured.

### Known limitations / later handoff

- No compensating/undo adjustment
- No free-form positive/negative deltas or caller-supplied totals
- No automatic application of all exceptions
- No quota repair
- No MeterDefinition versioning (rebuild/adjustment still use current immutable meter semantics)
- No `UsageAdjusted` integration event
- Stale `RUNNING` reconciliation rows still need operational cleanup (Phase 8A limitation)

## Consequences

Operators can apply quarantined canonical usage with auditable evidence. Finalized ordinary mutation remains forbidden. Reconciliation can verify the result only by creating a new immutable run.
