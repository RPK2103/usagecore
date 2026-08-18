# Kafka event catalogue

UsageCore currently publishes **one** business event type on Kafka. There is no Schema Registry, Avro, Protobuf, or Kafka Streams topology.

Module: [`libraries/event-contracts`](../../libraries/event-contracts/README.md).

## USAGE_RECEIVED

| Field | Value |
| --- | --- |
| Constant | `EventTypes.USAGE_RECEIVED` |
| Wire `eventType` | `UsageReceived` |
| Wire `eventVersion` | `1` (`EventVersions.V1`) |
| Topic | `usagecore.usage.received.v1` |
| DLQ topic | `usagecore.usage.received.v1.dlq` |
| Consumer group (runtime) | `usagecore-usage-pipeline-v1` |
| Partition key | `tenantId\|productKey\|meterKey` |
| Serialization | JSON envelope + payload |
| Delivery | **At-least-once** |

## Envelope

`EventEnvelope<UsageReceivedPayload>`:

| Field | Role |
| --- | --- |
| `eventId` | Identity of this emitted event instance. Consumer inbox key. |
| `eventType` / `eventVersion` | Contract identity. |
| `occurredAt` | Business event time (window assignment). |
| `tenantId` | From authenticated principal at ingest — not from the HTTP body. |
| `aggregateId` | Routing/aggregate hint on the envelope. |
| `correlationId` | HTTP `X-Correlation-Id` (business correlation). Distinct from `traceId`. |
| `traceId` | Distributed-trace evidence (`traceparent` / OTel). |
| `publishedAt` | Publisher timestamp. |
| `payload` | See below. |

JWT credentials and secrets must never appear on the envelope.

## Payload

`UsageReceivedPayload`:

| Field | Role |
| --- | --- |
| `productKey` | Product business key. |
| `meterKey` | Meter business key. |
| `quantity` | Requested quantity (meter semantics applied later). |
| `idempotencyKey` | Caller logical key for **HTTP ingestion** dedup. Not the consumer inbox key. |
| `principalSubject` | Authenticated subject evidence. |

## Deduplication layers

| Layer | Key | Effect |
| --- | --- | --- |
| HTTP ingest | tenant + product + meter + `idempotencyKey` | Same payload → replay 202; different payload → 409 |
| Transport | Kafka may deliver the same `eventId` more than once | Expected |
| Consumer inbox | `processed_event` unique on `eventId` | Second delivery is a successful no-op |

Correct phrase: **at-least-once transport with idempotent business effects.**

Not claimed: end-to-end exactly-once, schema evolution platform, or multi-event choreography.
