# System overview

UsageCore is a multi-tenant entitlement and usage platform. PostgreSQL is the transactional source of truth. Delivery semantics are at-least-once; end-to-end exactly-once is not claimed.

## Logical workloads

| Workload | Responsibility |
| --- | --- |
| control-plane | Catalog and commercial configuration: Tenant, Product, Feature, Plan, Contract, ContractVersion activation; **production Flyway owner** |
| entitlement-runtime | Authenticated entitlement checks against activated contract snapshots; decision evidence; no Control Plane compile-time dependency ([ADR-007](../adr/ADR-007-entitlement-runtime-read-architecture.md)) |
| usage-pipeline | Meter ingestion, aggregation, reconciliation against entitlements (not built yet) |

Build only workloads required by the active milestone. Kafka starts only after entitlement-runtime foundation exists. No frontend in this repo.

Shared Flyway SQL: [`libraries/database-migrations`](../../libraries/database-migrations/README.md).

## Boundaries

```
┌─────────────────────────────────────────────────────────┐
│ adapters (HTTP, messaging, persistence) — not Phase 0   │
├─────────────────────────────────────────────────────────┤
│ application services (use cases)                        │
├─────────────────────────────────────────────────────────┤
│ domain (entities, policies, invariants)                 │
│   — no Spring MVC / Kafka / JDBC / AWS / HTTP deps      │
└─────────────────────────────────────────────────────────┘
                              │
                              ▼
                     PostgreSQL (SoT)
```

See [ADR-001](../adr/ADR-001-application-boundaries.md).

## Tenancy

Shared PostgreSQL schema with tenant-aware isolation. Application code must scope all reads/writes by tenant. PostgreSQL RLS is deferred for v1 ([ADR-006](../adr/ADR-006-postgresql-rls.md); see also [ADR-002](../adr/ADR-002-postgresql-tenancy.md)).

## Commercial model (summary)

- **Plans** are reusable templates ([ADR-004](../adr/ADR-004-plan-vs-contract.md)).
- **Contracts / ContractVersions** are the commercial truth for a tenant–product relationship.
- Activated `ContractVersion` state (and entitlement snapshots) is immutable ([ADR-003](../adr/ADR-003-contract-historical-state.md)).
- Effective time uses half-open intervals `[effectiveFrom, effectiveUntil)` in UTC-compatible timestamps ([ADR-005](../adr/ADR-005-temporal-model.md)).

## Out of scope for Phase 0

Application services, controllers, JPA/entities, Flyway/Liquibase schema, Kafka, Kubernetes, AWS, Terraform.
