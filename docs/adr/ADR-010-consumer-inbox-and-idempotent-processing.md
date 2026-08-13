# ADR-010: Consumer inbox and idempotent UsageReceived processing

## Status

Accepted

## Context

Phase 5A publishes usage events through a transactional outbox. Kafka delivery is
**at-least-once**. The same `UsageReceived` envelope (same `eventId`) may be
delivered more than once, including when:

```
Kafka delivery
    ↓
consumer commits DB work
    ↓
process crashes before Kafka offset acknowledgement
    ↓
Kafka redelivers the same event
```

Without consumer-side idempotency, redelivery would create duplicate business
effects. Phase 5B adds a consumer inbox and canonical usage ledger so duplicate
delivery is safe.

This ADR does **not** claim exactly-once transport.

## Decision

### Why a consumer inbox exists

`processed_event` records that a given Kafka `eventId` has been successfully
processed by a named consumer. It is the durable claim that prevents a second
business effect for the same emitted event.

### `eventId` vs HTTP `idempotencyKey`

| Key | Layer | Purpose |
| --- | --- | --- |
| `idempotencyKey` | HTTP ingestion | Deduplicates the caller's logical command (`UNIQUE (tenant_id, idempotency_key)`) |
| `eventId` | Kafka / consumer | Deduplicates transport/redelivery of one durable emitted event |

Consumer idempotency is based on **`eventId` only**. Two different emitted events
that happen to share an HTTP idempotency key are independent ledger entries.

### At-least-once delivery semantics

```
at-least-once Kafka delivery
  +
idempotent business processing (inbox + ledger)
```

End-to-end exactly-once is **not** claimed.

### DB transaction boundary

For one valid `UsageReceived`:

```
BEGIN
  INSERT processed_event … ON CONFLICT (event_id) DO NOTHING RETURNING …
  if not claimed:
      -- successful duplicate no-op
      COMMIT / return
  else:
      INSERT usage_ledger (UNIQUE event_id)
COMMIT
```

Inbox claim and ledger insert succeed or fail together. Partial success
(`processed_event` without ledger, or ledger without `processed_event`) is not
allowed for a successfully processed event.

PostgreSQL uniqueness is the final concurrency authority.

### Offset acknowledgement strategy

Configuration (unchanged intent from Phase 4/5A):

- `spring.kafka.consumer.enable-auto-commit=false`
- `spring.kafka.listener.ack-mode=record`

Spring Kafka acknowledges the record only after the `@KafkaListener` method
returns successfully. The listener invokes the transactional processor; the
listener return therefore happens **after** the PostgreSQL commit.

If processing throws, the offset is not treated as successfully processed and
retry/redelivery remains possible.

### Duplicate handling

Same `eventId` delivered again:

1. `tryClaim` finds conflict → no-op
2. Listener returns successfully
3. Offset may be acknowledged
4. No second ledger row / business effect

Duplicate redelivery is **not** an error.

### Concurrency protection

Concurrent processors racing on the same `eventId`:

- Only one `INSERT … ON CONFLICT DO NOTHING RETURNING` claims the row
- The winner inserts the ledger row in the same transaction
- Losers observe an already-claimed `eventId` and no-op

`usage_ledger.event_id` is also `UNIQUE` as a second safety net.

### Retry / poison-message behavior

| Failure class | Examples | Behavior |
| --- | --- | --- |
| Transient | temporary PostgreSQL errors | Bounded retries (`usagecore.kafka.consumer-retry`) |
| Non-retryable | unsupported type/version, invalid payload, deserialize failure | No infinite retry; recover to DLQ |

DLQ topic: `usagecore.usage.received.v1.dlq` (configured as
`usagecore.kafka.topics.usage-received-dlq`).

DLQ handling is a bounded safety valve for v1 — not claimed as production-grade
poison-message recovery, replay tooling, or automated remediation.

### Known crash windows / limitations

```
DB transaction commits
    ↓
process dies before Kafka offset acknowledgement
    ↓
Kafka redelivers same eventId
    ↓
consumer inbox detects duplicate → successful no-op
```

That window is expected under at-least-once + idempotent processing.

Still out of scope: usage aggregates, quota consumption, billing periods,
reconciliation APIs, Kafka Streams, Schema Registry.

## Consequences

- Usage Pipeline remains independently deployable; no Control Plane / Entitlement
  Runtime compile-time dependency.
- Canonical raw usage history lives in `usage_ledger` for later metering phases.
- Operators must not interpret ledger insertion as quota/billing completion.
