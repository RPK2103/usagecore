# Event contracts

Versioned Kafka **transport** contracts and envelope types for UsageCore.

This module is intentionally narrow:

- event envelopes and payloads used on the wire
- explicit `eventType` / `eventVersion` constants

It is **not** a shared business-domain mega-library. Application modules must not put domain entities here.

Catalogue (topic, envelope, `eventId` vs HTTP `idempotencyKey`, at-least-once semantics): [docs/architecture/events.md](../../docs/architecture/events.md).

See [ADR-008](../../docs/adr/ADR-008-kafka-usage-topology.md).
