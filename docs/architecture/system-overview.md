# System overview

UsageCore is a multi-tenant entitlement and usage platform. PostgreSQL is the transactional source of truth. Delivery semantics are at-least-once; end-to-end exactly-once is not claimed.

## Logical workloads

| Workload | Responsibility |
| --- | --- |
| control-plane | Catalog and commercial configuration: Tenant, Product, Feature, Plan, MeterDefinition, Contract, ContractVersion activation; **production Flyway owner** |
| entitlement-runtime | Authenticated entitlement checks against activated contract snapshots; decision evidence; no Control Plane compile-time dependency ([ADR-007](../adr/ADR-007-entitlement-runtime-read-architecture.md)) |
| usage-pipeline | Durable usage ingestion, transactional outbox, idempotent consumer ledger + aggregates, **synchronous contract-aware quota consume**; no Control Plane / Entitlement Runtime compile-time dependency ([ADR-008](../adr/ADR-008-kafka-usage-topology.md)–[ADR-013](../adr/ADR-013-contract-aware-quota-enforcement.md)) |

Build only workloads required by the active milestone. No frontend in this repo.

Shared modules:

- Flyway SQL: [`libraries/database-migrations`](../../libraries/database-migrations/README.md)
- Kafka transport contracts: `libraries/event-contracts` (envelopes only — not a business-domain mega-library)

## Boundaries

```
┌─────────────────────────────────────────────────────────┐
│ adapters (HTTP, messaging, persistence)                 │
├─────────────────────────────────────────────────────────┤
│ application services (use cases)                        │
├─────────────────────────────────────────────────────────┤
│ domain (entities, policies, invariants)                 │
│   — no Spring MVC / Kafka / JDBC / AWS / HTTP deps      │
└─────────────────────────────────────────────────────────┘
                              │
                              ▼
                     PostgreSQL (SoT)
                              │
                     Kafka (usage transport)
```

See [ADR-001](../adr/ADR-001-application-boundaries.md).

## Tenancy

Shared PostgreSQL schema with tenant-aware isolation. Application code must scope all reads/writes by tenant. PostgreSQL RLS is deferred for v1 ([ADR-006](../adr/ADR-006-postgresql-rls.md); see also [ADR-002](../adr/ADR-002-postgresql-tenancy.md)).

Usage ingestion never accepts `tenantId` from the request body; tenant comes only from the validated JWT.

## Commercial model (summary)

- **Plans** are reusable templates ([ADR-004](../adr/ADR-004-plan-vs-contract.md)).
- **Contracts / ContractVersions** are the commercial truth for a tenant–product relationship.
- Activated `ContractVersion` state (and entitlement snapshots) is immutable ([ADR-003](../adr/ADR-003-contract-historical-state.md)).
- Effective time uses half-open intervals `[effectiveFrom, effectiveUntil)` in UTC-compatible timestamps ([ADR-005](../adr/ADR-005-temporal-model.md)).

## Usage pipeline (Phase 6C)

- Topic: `usagecore.usage.received.v1` (DLQ: `usagecore.usage.received.v1.dlq`)
- Partition key: `tenantId|productKey|meterKey` (ordering within partition; hot-partition trade-off documented in ADR-008)
- `POST /api/v1/usage/events` → HTTP 202 = durable PostgreSQL acceptance (ingestion + outbox) — **not** strict quota
- `POST /api/v1/usage/consume` → HTTP 200 commercial `ACCEPTED`/`REJECTED` with PostgreSQL-authoritative `quota_state` ([ADR-013](../adr/ADR-013-contract-aware-quota-enforcement.md))
- Consumer: `processed_event` inbox + immutable `usage_ledger` + lifetime `usage_aggregate` + event-time `usage_window_aggregate` keyed by `eventId`
- Aggregation from Control Plane `MeterDefinition` (`SUM` / `COUNT` / `MAX`, `MONTHLY` / `DAILY` UTC windows) via shared-schema JDBC read
- Meter → Feature via explicit `MeterDefinition.featureId` for contractual quota
- Window ownership uses `occurredAt`; late arrivals are accepted and update historical windows before commercial finalization
- Delivery remains at-least-once; duplicate redelivery is a successful no-op (aggregates not reapplied)
- Kafka Streams deferred — PostgreSQL UPSERT retained for transactional correctness with inbox/ledger
