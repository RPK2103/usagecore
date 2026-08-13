# ADR-013: Contract-aware quota enforcement

## Status

Accepted

## Context

Phase 6B provides event-time window aggregates and asynchronous metering.
Phase 6C must answer:

- How much contractual quota is configured?
- How much has been consumed in the applicable usage window?
- Can a requested consumption be accepted without exceeding the contract limit
  under concurrency?

A read-only entitlement check (`POST /api/v1/entitlements/check`) cannot enforce
strict quota: `remaining = limit - currentUsage` races under concurrent admits.

## Decision

### Service ownership

**Usage Pipeline** owns `POST /api/v1/usage/consume`.

Rationale:

- Accepted consumption must atomically persist quota admission **and** durable
  usage/outbox intent (same PostgreSQL transaction).
- Usage Pipeline already owns ingestion + transactional outbox + Kafka.
- Entitlement Runtime remains the low-latency **read-only** commercial check path.
- No compile-time dependency on Control Plane or Entitlement Runtime; commercial
  state is read via narrow JDBC against the shared schema.

### Entitlement check vs quota consumption

| API | Mutating? | Purpose |
| --- | --- | --- |
| `POST /api/v1/entitlements/check` | No (decision evidence only) | Commercial allow/deny / limit awareness |
| `POST /api/v1/usage/events` | Yes (ingestion + outbox) | Async telemetry metering — **no** strict quota |
| `POST /api/v1/usage/consume` | Yes (quota + ingestion + outbox) | Synchronous strict quota admission |

Clients requiring hard enforcement use `/consume`. `/events` must not be claimed
to prevent quota overshoot.

### Meter → feature mapping

`MeterDefinition.featureId` (FK to `feature`) is the explicit governing Feature.

- Required at meter create time (Control Plane Phase 6C APIs).
- Immutable after creation for newly bound meters (no generic update endpoint).
- Never inferred from `meterKey == featureKey` or string similarity.
- Consume resolves meter → feature key → effective activated entitlement.

**Legacy upgrade note:** V10 adds `feature_id` as nullable so existing V8/V9 meter rows
survive Flyway upgrade without fabricated feature bindings. A `CHECK (feature_id IS NOT NULL)
NOT VALID` constraint requires feature binding on new/updated rows while leaving pre-existing
unbound meters intact. Unbound meters cannot participate in strict quota consumption
(`METER_NOT_BOUND_TO_FEATURE`) until explicitly remediated. A later migration may `VALIDATE`
the constraint after backfill.

### Contract resolution timestamp

Quota and entitlement applicability use **`occurredAt`** (event/commercial time),
not processing time and not HTTP receive time.

Half-open activated ContractVersion intervals `[effectiveFrom, effectiveUntil)`
are resolved exactly as entitlement-runtime commercial reads.

### Quota window semantics

Reuse Phase 6B `UsageWindowResolver`:

- UTC calendar windows
- `DAILY` / `MONTHLY`
- Half-open `[window_start, window_end)`
- Derived from `occurredAt` + `MeterDefinition.aggregationWindow`

No separate quota window algorithm.

### Aggregation types

| Type | Quota contribution | Phase 6C |
| --- | --- | --- |
| `SUM` | requested `quantity` | Supported |
| `COUNT` | `1` per accepted consume | Supported |
| `MAX` | n/a (not additive) | `REJECTED` / `UNSUPPORTED_QUOTA_METER_TYPE` for `LIMITED` |

`ENABLED` accepts without numeric quota (including `MAX` meters) and still emits
durable usage. `DISABLED` rejects with no quota/usage effect.

### Authoritative quota state vs reporting aggregate

| Store | Role |
| --- | --- |
| `quota_state` | Synchronous admission counter (PostgreSQL authority) |
| `quota_consumption` | Durable commercial decision + tenant-scoped idempotency |
| `usage_ledger` / `usage_window_aggregate` | Async metering/reporting |

They may temporarily diverge due to Kafka lag. Reporting lag **cannot** authorize
overshoot because admission never reads reporting aggregates.

### PostgreSQL concurrency mechanism

Atomic conditional update:

1. Ensure `quota_state` row for `(tenant, meter, window)` exists (`consumed = 0`).
2. `UPDATE ... SET consumed = consumed + :contrib WHERE consumed + :contrib <= :limit RETURNING consumed`.

Empty `RETURNING` ⇒ `QUOTA_EXHAUSTED` (all-or-nothing; no partial consume).

Identical idempotency keys serialize via `pg_advisory_xact_lock(hashtext(tenant), hashtext(key))`
before any quota mutation, then `UNIQUE (tenant_id, idempotency_key)` on
`quota_consumption`.

No Java locks. No Redis. No distributed locks.

### Idempotency

- Caller key: `idempotencyKey` (tenant-scoped unique).
- Accepted emits stable `eventId` for outbox/Kafka.
- Identical retry → same decision / identity; no second quota effect.
- Conflicting payload → HTTP `409 IDEMPOTENCY_CONFLICT`.
- Rejections are persisted so identical retries remain deterministic.

### Transaction boundary

One PostgreSQL transaction:

```
acquire idempotency advisory lock
resolve meter + occurredAt window + effective entitlement
LIMITED → atomic quota_state tryConsume
persist quota_consumption decision
if ACCEPTED → usage_ingestion + outbox_event PENDING
COMMIT
```

Accepted commercial decision cannot exist without durable usage intent.
Usage intent cannot exist if the transaction rolls back.

### Outbox / Kafka

- No synchronous Kafka publish on the consume path.
- Kafka outage after commit leaves `ACCEPTED` + `PENDING` outbox; publisher recovers.
- Delivery remains at-least-once; consumer inbox prevents double metering.

### Late events / finalization

Before commercial-period finalization (Phase 7), late usage against an older
still-mutable window may still be evaluated and may change `quota_state` for
that historical window. No `FINALIZED` rejection in Phase 6C.

## Consequences

- Strict concurrent exhaustion is enforceable with evidence.
- `/entitlements/check` stays non-mutating.
- `/usage/events` remains telemetry-only regarding quota.
- Reconciliation of admission vs reporting counters is deferred.
- MAX strict quota remains unsupported until rigorously defined.

## Known limitations

- No commercial period OPEN/CLOSING/RECONCILING/FINALIZED.
- No UsageAdjustment / reconciliation engine / billing / credits.
- Mid-window contract limit changes are edge cases; enforcement uses the limit
  resolved for the request's `occurredAt`.
- Distinct idempotency namespaces: sharing keys across `/events` and `/consume`
  can conflict on `usage_ingestion` uniqueness (documented; not a dual-write hole
  because the consume transaction rolls back together).
- Pre-V10 meters may remain temporarily unbound (`feature_id` null) after upgrade;
  they are rejected from strict quota until explicitly remediated. UsageCore does
  not invent feature mappings from meter keys.
