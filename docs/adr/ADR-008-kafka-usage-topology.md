# ADR-008: Kafka usage topology and partitioning

## Status

Accepted

## Context

Phase 4 introduces `applications/usage-pipeline` for authenticated usage ingestion.
Usage processing is asynchronous: HTTP acceptance must not imply aggregation, quota
consumption, or billing mutation. Kafka provides at-least-once transport between
ingestion and later metering phases.

## Decision

### Why Kafka exists

- Decouple ingestion latency from downstream metering/aggregation work.
- Preserve an ordered stream per tenant/product/meter for later commercial processing.
- Establish real producer/consumer infrastructure before Phase 5 correctness machinery.

### Why usage processing is asynchronous

- `POST /api/v1/usage/events` returns **202 Accepted** only after Kafka acknowledges
  publication.
- 202 means “accepted for asynchronous processing”, not “usage totals updated”.

### Initial topic

| Config key | Default |
| --- | --- |
| `usagecore.kafka.topics.usage-received` | `usagecore.usage.received.v1` |

One topic for the `UsageReceived` event type (versioned via envelope `eventVersion`),
not one topic per Java class.

### Partition key

```
tenantId|productKey|meterKey
```

Intent:

- preserve ordering for one tenant/product/meter stream
- distribute different tenants/meters across partitions
- avoid random `eventId` keys that destroy meaningful ordering

**Trade-off:** a very high-volume tenant/product/meter can still become a hot partition.
Later performance evidence may justify a more sophisticated strategy. This does **not**
eliminate hot partitions.

### Ordering guarantee

- Ordering is preserved **within a partition**.
- Same partition key → same partition → ordered for that stream.
- **No global ordering** across partitions or tenants.

### Consumer-group semantics

| Config key | Default |
| --- | --- |
| `usagecore.kafka.consumer-group` | `usagecore-usage-pipeline-v1` |

- Partitions are assigned among consumers in one group.
- One partition is processed by at most one consumer in that group at a time.
- Consumer count beyond partition count does not increase useful parallelism.

### Event time vs processing time

- `occurredAt` is caller-supplied business/event time and is preserved unchanged.
- `publishedAt` captures emission/processing metadata at publish time.
- Phase 6 metering will use this distinction for windows and late events.

### Phase 4 correctness limitations

- Delivery is **at-least-once**. End-to-end exactly-once is not claimed.
- Consumer processing is **not** claimed idempotent.
- No transactional outbox, inbox/deduplication, retry/DLQ architecture, or quota effects.
- Phase 5 adds distributed correctness (inbox, dedup, deliberate retry/poison handling).

### Shared contracts

Versioned JSON envelopes live in `libraries/event-contracts` (transport only).
`eventId` identifies the emitted event instance; caller `idempotencyKey` identifies the
logical usage operation (HTTP ingestion dedup). Consumer redelivery dedup uses `eventId`
([ADR-010](ADR-010-consumer-inbox-and-idempotent-processing.md)).

## Consequences

- Usage Pipeline deploys independently of Control Plane and Entitlement Runtime.
- Schema compatibility is controlled by explicit `eventVersion` and tests initially
  (no Schema Registry in this phase).
- Operators must not interpret HTTP 202 as commercial completion.
