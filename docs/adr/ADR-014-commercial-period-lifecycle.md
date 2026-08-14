# ADR-014: Commercial period lifecycle

## Status

Accepted (Phase 7)

## Context

Phase 6B accepts late usage into historical event-time windows. Phase 6C adds strict quota admission. Neither defines when commercial history for a time range becomes frozen.

UsageCore must distinguish:

- **UsageWindow** — derived event-time aggregation bucket (`tenant`, `meter`, `[window_start, window_end)`)
- **CommercialPeriod** — commercial lifecycle governing whether usage for a tenant+product time range may still mutate commercial derived state

These must not be collapsed into a single table.

## Decision

### Explicit `commercial_period`

Identity (v1):

```text
tenant_id + product_id + [period_start, period_end)
```

Half-open UTC intervals `[period_start, period_end)`, consistent with contract/window semantics.

Statuses:

```text
OPEN → CLOSING → RECONCILING → FINALIZED
```

`FINALIZED` is terminal for ordinary usage/quota mutation. No unfinalize. Phase 8B may apply an explicit `UsageAdjustment` without changing period status ([ADR-016](ADR-016-explicit-usage-adjustments.md)).

### Ownership

- **Control Plane** owns create / read / explicit lifecycle transitions (admin API).
- **Usage Pipeline** reads period state via narrow JDBC (`CommercialPeriodReader`) during consumer and `/usage/consume` processing.
- No Control Plane HTTP calls during usage processing.
- No compile-time dependency between Usage Pipeline and Control Plane.

### Overlap protection

PostgreSQL exclusion constraint (`btree_gist` + `tstzrange ... &&`) prevents overlapping periods for the same tenant+product. Concurrent creates race at the database, not in Java find-then-insert.

### Transition concurrency

Transitions use conditional UPDATE:

```sql
UPDATE commercial_period
SET status = :next, ...
WHERE id = :id AND status = :expected
RETURNING ...
```

Empty `RETURNING` → deterministic 409 invalid transition.

### Finalization vs usage race

Usage/quota paths that resolve a commercial period take `SELECT ... FOR SHARE` on the matching row in the same transaction as ledger/aggregate (or quarantine) work.

Lifecycle transitions take a conflicting row lock via `UPDATE`. Therefore either:

1. usage observes pre-finalization status and quarantines (RECONCILING), then finalization succeeds; or
2. finalization commits first and usage observes `FINALIZED` and quarantines.

There is no ambiguous interleaving that mutates aggregates after finalization wins.

### Behavior by state

| State | Async `/usage/events` consumer | Strict `/usage/consume` |
| --- | --- | --- |
| NO_PERIOD | Phase 6 behavior (ledger + aggregates) | Phase 6C behavior |
| OPEN | ledger + aggregates | normal entitlement/quota |
| CLOSING | ledger + aggregates (controlled late arrivals) | REJECTED `PERIOD_CLOSING` |
| RECONCILING | ledger + `commercial_usage_exception`; **no** aggregate mutation | REJECTED `PERIOD_RECONCILING` |
| FINALIZED | ledger + `commercial_usage_exception`; **no** aggregate mutation | REJECTED `PERIOD_FINALIZED` |

HTTP `202` on `/usage/events` remains “durably accepted for asynchronous processing”. Period classification happens in the consumer. Do not synchronously reject historical events on ingest merely to avoid quarantine.

### Canonical ledger decision

Blocked RECONCILING/FINALIZED events still write immutable `usage_ledger` evidence, then `commercial_usage_exception`, and do **not** mutate:

- `usage_aggregate` (lifetime)
- `usage_window_aggregate`
- `quota_state`

Rationale: preserve knowledge that the event occurred while protecting finalized commercial derived state. Lifetime totals may temporarily diverge from commercially applied window sums until Phase 8 reconciliation/adjustment.

`commercial_usage_exception` is quarantine evidence, **not** `UsageAdjustment`.

### Quota state on finalization

Finalization does not delete or rewrite `quota_state`. It is historical admission state. Closed period states simply refuse further `/usage/consume` mutation.

### Manual finalization vs reconciliation

Phase 7 finalization is administrative. Allowing `RECONCILING → FINALIZED` does **not** prove aggregates were rebuilt or corrected. Phase 8 owns reconciliation/adjustment engines.

### Audit

Append-only `commercial_period_transition` records `from_status`, `to_status`, `principal_id`, `occurred_at`, optional `correlation_id`. JWT material is never stored.

## Consequences

### Positive

- Clear commercial immutability boundary without overloading window aggregates
- Concurrent-safe overlap and transition semantics via PostgreSQL
- Honest async ingest semantics (202 ≠ commercial acceptance)
- Phase 8 receives durable quarantine input

### Trade-offs / limitations

- No automatic period scheduler / timezone calendars
- No reconciliation rebuild or UsageAdjustment application
- No billing/invoicing
- NO_PERIOD compatibility means unseeded times remain mutable under Phase 6 rules
- Lifetime aggregate may exclude quarantined events until Phase 8

## Related

- ADR-003 Contract historical state (distinct immutability dimension)
- ADR-012 Event-time and windowed metering
- ADR-013 Contract-aware quota enforcement
