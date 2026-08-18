# ADR-019: Resilience and failure recovery

## Status

Accepted (Phase 10)

## Context

Phases 5–9 established durable HTTP acceptance, transactional outbox, at-least-once Kafka, consumer inbox idempotency, commercial-period quarantine, and observability. Those mechanisms implied specific failure windows (Kafka ACK before outbox `PUBLISHED`; consumer DB commit before offset acknowledgement) but did not prove recovery with real broker/database unavailability.

Phase 10 proves selected failure windows: fail → observe → preserve invariants → recover → verify business state. It does not add a fourth service, Resilience4j, Spring Retry, Toxiproxy, circuit breakers everywhere, or automatic commercial repair.

## Decision

### Failure model (critical path)

```
HTTP POST /api/v1/usage/events
        ↓
PostgreSQL transaction
  usage_ingestion
  outbox_event PENDING
        ↓ COMMIT          ← durable acceptance / HTTP 202
        ↓
asynchronous outbox publisher
  claim PENDING (FOR UPDATE SKIP LOCKED)
  Kafka send + broker ACK
  mark PUBLISHED
        ↓
Kafka at-least-once delivery
        ↓
consumer @Transactional
  processed_event claim
  usage_ledger
  aggregate or commercial_usage_exception
        ↓ COMMIT
listener return
        ↓
Kafka offset acknowledgement
```

| Step | Commit / ack point | If this fails |
| --- | --- | --- |
| HTTP ingest DB commit | PostgreSQL | No 202; no ingestion/outbox rows |
| Kafka publish | Broker ACK (not HTTP) | Row stays PENDING; HTTP 202 already returned |
| Mark PUBLISHED | PostgreSQL after Kafka ACK | Classic duplicate window: Kafka may already contain the event |
| Consumer DB commit | PostgreSQL inbox+ledger | Rollback; Kafka redelivers |
| Offset ack | Kafka (after listener return) | Redelivery; inbox no-op |

HTTP 202 means durably accepted into PostgreSQL for asynchronous processing. It does not mean Kafka published, ledger applied, quota changed, or billing updated.

### PostgreSQL dependency

PostgreSQL is the correctness authority. If the ingest transaction cannot commit, Usage Pipeline must not return successful durable acceptance. Readiness includes `db` and does not include Kafka.

Control Plane mutating APIs and Entitlement Runtime evaluation also require PostgreSQL. Database unavailability is mapped to HTTP 503 `SERVICE_UNAVAILABLE` without stack traces or JWT material.

Pause/unpause of a live container is the Phase 10 experiment. Destroying the database volume is disaster recovery and is out of scope.

### Kafka degradation

Kafka unavailability during HTTP ingest does not prevent 202 when PostgreSQL can commit. Outbox rows remain `PENDING`. After Kafka recovers, the publisher sends the **stored** envelope (same `eventId`, payload, `occurredAt`).

Concurrent publisher executions do not provide a global publication order. SUM/COUNT/MAX aggregates are order-independent. Partition order applies only after records are appended by a producer sequence.

### Outbox crash windows

1. **Before Kafka send** — transaction rolls back; row remains `PENDING`; retry publishes the original envelope.
2. **After Kafka ACK, before `PUBLISHED`** — expected at-least-once duplicate window. Retry uses the same `eventId`. Consumer inbox makes duplicate delivery a successful no-op.

Exactly-once Kafka is not claimed.

### Consumer crash windows

1. **Before DB commit** — inbox/ledger/aggregate roll back together; record remains redeliverable.
2. **After DB commit, before offset ack** — redelivery hits `processed_event`; no second ledger/aggregate contribution.

### Poison events / DLQ

Unsupported type/version, invalid payload, and unknown meters are non-retryable and recover to `usagecore.usage.received.v1.dlq` (or the configured DLQ topic). Transient failures use bounded `FixedBackOff` (`usagecore.kafka.consumer-retry`).

If processing fails **and** DLQ publication itself cannot complete, Spring Kafka’s `DeadLetterPublishingRecoverer` can fail (`recoverer_failure` metric). There is no second DLQ. Poison-message recovery is **not** guaranteed when the DLQ destination is unavailable. Automatic DLQ replay is not provided.

### Delayed delivery vs CommercialPeriod

Consumer evaluates **current** CommercialPeriod state at processing time, not HTTP acceptance time. An event accepted while `OPEN` and delivered after `FINALIZED`/`RECONCILING` remains canonical ledger evidence and is quarantined; finalized aggregates are not silently mutated. Quota vs commercial aggregate divergence is visible through reconciliation. Stale `RUNNING` reconciliation after a process crash remains a documented limitation (no lease/reaper in Phase 10).

### Retry policies (audit)

| Operation | Attempts | Backoff | Retryable | Non-retryable | Outcome |
| --- | --- | --- | --- | --- | --- |
| Outbox publisher | Until `PUBLISHED` (scheduler / `publishBatch`) | Fixed delay (`usagecore.outbox.publisher.fixed-delay-ms`, default 1s) | Kafka send/timeout | None at publisher (invalid events should not be ingested) | Stored envelope retried; `eventId` immutable |
| Kafka consumer | `usagecore.kafka.consumer-retry.max-attempts` (default 3, including first) | Fixed `interval-ms` (default 200ms) | Unexpected/transient | `UnsupportedUsageEventException`, `InvalidUsageEventException`, `UnknownUsageMeterException` | Bounded retry then DLQ |
| JDBC / Hikari | Driver/pool reconnect | Pool `connection-timeout` | Connection loss | Constraint violations | No application-level retry annotation |
| OTel exporter | SDK exporter behavior | Not application-controlled | Export failure | n/a | Must not affect commercial commits |
| HTTP ingest | None | n/a | n/a | Validation, auth, idempotency conflict | 202 only after DB commit |

v1 accepts bounded scheduler/consumer retry. Recovery can produce a burst of retries; no exponential-backoff framework was added.

### Process restart

PENDING outbox rows are durable. A restarted publisher discovers them via `claimPending`. A full JVM stop/start smoke is **REASONED BUT NOT EXECUTED** here (no Kubernetes process orchestration). Persistence/retry is proven in integration tests.

### Readiness

| Workload | Readiness | Kafka down |
| --- | --- | --- |
| Usage Pipeline | `readinessState` + `db` | HTTP ingest may still 202; readiness stays up |
| Control Plane | `readinessState` + `db` | n/a |
| Entitlement Runtime | `readinessState` + `db` | n/a |

Liveness does not include Kafka or DB.

### Observability during failures

Phase 9 signals remain authoritative for **operations**, not commercial correctness. Counters may increment inside a transaction that later rolls back (outbox publish failure vs Kafka ACK-then-crash). Metric counts may not equal committed row counts.

### What Phase 10 proves

- Kafka publication outage does not erase durable acceptance
- Kafka recovery drains PENDING outbox without regenerating `eventId`
- ACK-before-PUBLISHED may duplicate transport and cannot duplicate business state
- Consumer pre-commit failure leaves no partial inbox/ledger/aggregate
- Consumer commit/offset gap is closed by inbox idempotency
- PostgreSQL unavailability prevents false 202 / false entitlement success / Control Plane mutation
- Poison events go to DLQ without permanently wedging a partition
- Delayed delivery after finalization quarantines rather than rewriting finalized aggregates
- Recovery does not require manual `UPDATE` of canonical/commercial tables

### What remains unproven / deferred

- Live multi-process JVM restart drill (deferred to deployment phase)
- Kafka cluster disaster recovery / multi-broker loss
- PostgreSQL volume destruction / backup restore
- DLQ destination outage (no second DLQ)
- Automatic reconciliation lease/reaper for stale `RUNNING`
- Multi-region failover, Kubernetes, AWS
- Load/performance (Phase 11)
- Production HA / zero data loss / exactly-once

## Alternatives considered

- **Resilience4j / Spring Retry / circuit breakers everywhere** — rejected; existing outbox, inbox, and bounded Kafka error handler are the correctness mechanisms.
- **Toxiproxy** — not required; Docker pause preserves ports and data for broker/DB unavailability.
- **Production `/admin/crash` or `SIMULATE_*` flags** — rejected; chaos stays in tests.
- **Claiming exactly-once after inbox** — rejected; transport remains at-least-once.

## Consequences

- Testcontainers pause/unpause is the primary failure injector.
- Unique consumer groups and topics isolate Kafka resilience tests from Phase 5–9 suites.
- Operators restore Kafka/PostgreSQL and let application retry; they must not rewrite `outbox_event`, `processed_event`, `usage_ledger`, or aggregates.
