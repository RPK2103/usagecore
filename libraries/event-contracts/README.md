# Event contracts

Versioned Kafka **transport** contracts and envelope types for UsageCore.

This module is intentionally narrow:

- event envelopes and payloads used on the wire
- explicit `eventType` / `eventVersion` constants

It is **not** a shared business-domain mega-library. Application modules must not put domain entities here.

See [ADR-008](../../docs/adr/ADR-008-kafka-usage-topology.md).
