# ADR-001: Application boundaries

## Status

Accepted

## Context

UsageCore will grow into control-plane, entitlement-runtime, and usage-pipeline workloads. Mixing framework and infrastructure into domain logic makes commercial invariants hard to test and easy to violate.

## Decision

- Use pragmatic hexagonal / clean architecture.
- Domain layer has no dependencies on Spring MVC, Kafka, PostgreSQL drivers/APIs, AWS SDKs, or HTTP clients.
- Application layer orchestrates use cases; adapters implement ports (persistence, messaging, HTTP).
- Build only workloads required by the current milestone.
- No frontend in the core repository.
- Kafka is introduced only after entitlement-runtime foundation.

## Consequences

- Domain can be unit-tested without containers or brokers.
- Adapters may use Spring Data, JDBC, Kafka clients, etc., behind ports.
- Early modules stay small; avoid premature multi-service split.
