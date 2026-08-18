# Source of truth

PostgreSQL is the transactional correctness authority. Kafka is usage transport. Dashboards, metrics, and rebuild reports describe or compare state; they do not replace it.

This distinction is one of UsageCore’s strongest engineering stories.

## Authority table

| Concern | Authority | Notes |
| --- | --- | --- |
| Commercial configuration | Activated `ContractVersion` + `entitlement` snapshots | Plans are templates only ([ADR-004](../adr/ADR-004-plan-vs-contract.md)). |
| Canonical usage evidence | `usage_ledger` | Immutable business fact after consumer commit. |
| Async ingestion durability | `usage_ingestion` + `outbox_event` | HTTP 202 means these rows committed, not that Kafka or the consumer finished. |
| Duplicate / redelivery authority | `processed_event` | Unique `(consumer, eventId)` — duplicate transport is a no-op. |
| Reporting state | `usage_aggregate` / `usage_window_aggregate` | Derived. Rebuildable. Not quota authority. |
| Strict quota authority | `quota_state` / `quota_consumption` | PostgreSQL conditional UPDATE is the concurrency control. |
| Commercial lifecycle | `commercial_period` | Separate from event-time usage windows. |
| Delayed / finalized exception evidence | `commercial_usage_exception` | Ledger may still exist; aggregates are not silently rewritten. |
| Reconciliation evidence | `reconciliation_run` / `reconciliation_item` | Report, not repair. |
| Explicit corrections | `usage_adjustment` | Only `APPLY_QUARANTINED_USAGE`; quota state is not mutated. |
| Entitlement decision evidence | `entitlement_decision` | Append-oriented check history; does not consume quota. |
| Tenant identity | Validated JWT `tenant_id` | Never body, header, or path as authorization evidence. |
| Transport | Kafka topic `usagecore.usage.received.v1` | At-least-once. Not source of truth. |

## Table categories

| Category | Examples | Mutability |
| --- | --- | --- |
| Canonical | `usage_ledger` | Append-oriented commercial evidence |
| Derived | `usage_aggregate`, `usage_window_aggregate` | Updated in consumer (or adjustment) transactions; rebuildable from ledger + adjustments |
| Admission | `quota_state`, `quota_consumption` | Authoritative remaining/consumed for strict consume |
| Operational | `usage_ingestion`, `outbox_event`, `processed_event` | Durability, publication, and inbox claim |
| Historical commercial | activated `contract_version`, `entitlement`, `commercial_period_transition` | Activated terms are not rewritten in place |
| Reconciliation / correction | `reconciliation_run`, `reconciliation_item`, `usage_adjustment`, `commercial_usage_exception` | Immutable completed evidence; corrections are explicit |

Physical DDL lives in [`libraries/database-migrations`](../../libraries/database-migrations/README.md) (Flyway V1–V13). Control Plane owns production migrations.

## What is not authority

- Grafana / Prometheus / traces
- Kafka offsets or topic contents
- Plan rows after a contract version is activated
- HTTP 202 alone as “usage applied”
- A reconciliation MATCH as automatic finalize
