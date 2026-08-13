# ADR-009: Transactional outbox and usage ingestion idempotency

## Status

Accepted

## Context

Phase 4 accepted usage only after a synchronous Kafka publish acknowledgement.
That coupled HTTP durability to broker availability and provided no business
idempotency for retries. Phase 5A moves durable acceptance into PostgreSQL and
publishes asynchronously via a transactional outbox.

Consumer inbox / processed-event deduplication is implemented in Phase 5B
([ADR-010](ADR-010-consumer-inbox-and-idempotent-processing.md)).

## Decision

### Why HTTP no longer waits for Kafka

- A valid usage command must be durably accepted when PostgreSQL can commit.
- Kafka availability must not determine HTTP 202.
- `POST /api/v1/usage/events` returns **202 Accepted** only after a successful
  PostgreSQL commit of `usage_ingestion` + matching `outbox_event`.

**202 means:** durably accepted for asynchronous processing.

**202 does not mean:** Kafka processed, usage aggregated, quota updated, or billing updated.

### Atomic ingestion + outbox transaction

Within one transaction the usage-pipeline:

1. Derives `tenantId` from the authenticated JWT (request body never supplies it)
2. Allocates a stable `eventId`
3. Persists `usage_ingestion`
4. Builds the existing `UsageReceived` `EventEnvelope` (`eventVersion = 1`)
5. Persists `outbox_event` with the exact serialized envelope
6. Commits, then returns HTTP 202

Rollback leaves neither ingestion nor outbox row.

### Tenant-scoped idempotency key

`UNIQUE (tenant_id, idempotency_key)` is the concurrency authority.

| Case | Result |
| --- | --- |
| Same tenant + same key + same logical payload | 202, same `eventId`, no extra rows (`idempotentReplay=true`) |
| Same tenant + same key + different payload | **409** `IDEMPOTENCY_CONFLICT` |
| Different tenants + same key | Independent acceptances |

Logical payload identity (compared on persisted fields):

- `productKey`
- `meterKey`
- `quantity`
- `occurredAt`

`principalId` is **not** part of the business idempotency identity — multiple
authorized service identities for one tenant may retry the same logical operation.

Inserts use `ON CONFLICT (tenant_id, idempotency_key) DO NOTHING` so concurrent
identical submissions resolve to one ingestion/outbox pair without check-then-insert races.

### Outbox at-least-once publication

A bounded publisher:

1. Claims `PENDING` rows with `FOR UPDATE SKIP LOCKED`
2. Publishes the **stored** envelope JSON to Kafka using the stored partition key
3. Waits for broker acknowledgement
4. Marks the row `PUBLISHED` only after acknowledgement

Retries reuse the same `eventId` and serialized envelope. Exactly-once publication
is **not** claimed.

### Kafka-ack / DB-commit crash window

```
Kafka accepts event
        ↓
process dies before PUBLISHED commit
        ↓
outbox row remains PENDING
        ↓
event may be published again (same eventId)
```

This is expected at-least-once behavior. Phase 5B consumer idempotency
([ADR-010](ADR-010-consumer-inbox-and-idempotent-processing.md)) makes
duplicate Kafka delivery safe.

v1 holds row locks while awaiting Kafka acknowledgement (simplicity over leasing).
Slow brokers extend lock duration — documented trade-off, not a distributed lock service.

### Why consumer idempotency is required next

Producer-side outbox alone cannot make end-to-end processing once-only under
at-least-once Kafka delivery. Phase 5B adds consumer inbox / processed-event
protection before commercial side effects ([ADR-010](ADR-010-consumer-inbox-and-idempotent-processing.md)).

## Consequences

- Usage Pipeline may use PostgreSQL via JDBC; Control Plane remains production Flyway owner.
- Event contract stays `UsageReceived` / `usagecore.usage.received.v1` (no new event type).
- Consumer processing is idempotent via inbox + ledger in Phase 5B (ADR-010).
