# Roadmap

Milestones are sequential. Each builds only the workloads it needs.

## Phase 0 — Repository foundation

- Cursor project rules
- Architecture docs and ADRs
- No application code, schema, or infra

## Phase 1 — Control-plane domain foundation

- Maven multi-module skeleton: domain + application + adapters as needed
- Domain: Tenant, Product, Feature, Plan, PlanFeature, Contract, ContractVersion, Entitlement
- PostgreSQL persistence for control-plane only
- Draft/activate contract version flows
- Tenant-scoped APIs (no frontend)

## Phase 2A — Control Plane security (foundation)

- Spring Security OAuth2 resource server (JWT) on `/api/v1`
- Tenant context from validated `tenant_id` claim; RBAC roles
- Security audit evidence; RLS deferred ([ADR-006](adr/ADR-006-postgresql-rls.md))

## Phase 3 — Entitlement runtime

- Independently deployable `applications/entitlement-runtime`
- Authenticated `POST /api/v1/entitlements/check` against activated ContractVersion snapshots
- Shared PostgreSQL commercial SoT with no Control Plane compile-time dependency ([ADR-007](adr/ADR-007-entitlement-runtime-read-architecture.md))
- Shared Flyway resources in `libraries/database-migrations`; Control Plane owns production migrations
- No Kafka, metering, remaining quota, or Redis yet

## Phase 4 — Usage pipeline Kafka foundation

- Independently deployable `applications/usage-pipeline`
- Authenticated `POST /api/v1/usage/events` → Kafka `usagecore.usage.received.v1`
- Shared `libraries/event-contracts` (transport envelopes only)
- Deterministic partition key `tenantId|productKey|meterKey`
- Testcontainers Kafka evidence; local KRaft Kafka in Docker Compose

## Phase 5A — Durable ingestion + transactional outbox

- PostgreSQL `usage_ingestion` + `outbox_event` (Flyway V6); HTTP 202 after DB commit
- Tenant-scoped idempotency key; 409 on same-key/different-payload
- Asynchronous outbox publisher (`FOR UPDATE SKIP LOCKED`); at-least-once to Kafka
- [ADR-009](adr/ADR-009-transactional-outbox-ingestion-idempotency.md)

## Phase 5B — Consumer correctness

- Consumer inbox (`processed_event`) + canonical `usage_ledger` (Flyway V7)
- Idempotent Kafka consumer keyed by `eventId`; duplicate redelivery is a successful no-op
- Bounded retry + DLQ `usagecore.usage.received.v1.dlq` for poison/non-retryable events
- [ADR-010](adr/ADR-010-consumer-inbox-and-idempotent-processing.md)

## Phase 6A — Meter definitions + deterministic aggregation

- Control Plane `MeterDefinition` catalogue (`SUM` / `COUNT` / `MAX`) — Flyway V8
- Consumer transaction extended: inbox + ledger + PostgreSQL atomic `usage_aggregate` UPSERT
- Usage Pipeline JDBC meter lookup (no Control Plane compile-time dependency)
- Unknown/inactive meter → non-retryable → DLQ; no silent meter creation
- Kafka Streams deferred ([ADR-011](adr/ADR-011-metering-and-aggregation.md))

## Phase 6B — Event-time windows + late-event semantics

- `MeterDefinition.aggregationWindow` (`MONTHLY` / `DAILY`) — UTC half-open intervals
- Derived `usage_window_aggregate` + `usage_ledger.is_late` — Flyway V9
- Window assignment from `occurredAt`; late events accepted and update historical windows
- Semantic meter fields immutable after create ([ADR-012](adr/ADR-012-event-time-and-windowed-metering.md))
- Kafka Streams re-evaluated and still deferred
- No commercial-period finalization, billing, or adjustments yet

## Phase 6C — Contract-aware quota enforcement

- `POST /api/v1/usage/consume` in Usage Pipeline — synchronous strict quota admission
- `MeterDefinition.featureId` explicit meter → feature mapping (Flyway V10)
- Authoritative `quota_state` + durable `quota_consumption` (idempotent); reporting aggregates remain async
- SUM / COUNT supported; LIMITED + MAX → `UNSUPPORTED_QUOTA_METER_TYPE`
- PostgreSQL conditional UPDATE is concurrency authority; transactional outbox on accept
- `/entitlements/check` remains read-oriented; `/usage/events` remains telemetry without strict quota
- [ADR-013](adr/ADR-013-contract-aware-quota-enforcement.md)

## Phase 7 — Commercial period lifecycle

- Explicit `CommercialPeriod` (`OPEN` → `CLOSING` → `RECONCILING` → `FINALIZED`) — Flyway V11
- Separate from event-time `UsageWindow`; Control Plane admin API + Usage Pipeline JDBC reader
- Overlap exclusion + atomic transitions; `FOR SHARE` serializes finalization vs usage mutation
- Async blocked events: ledger + `commercial_usage_exception` (no aggregate mutation)
- Strict `/usage/consume` rejects CLOSING/RECONCILING/FINALIZED; NO_PERIOD preserves Phase 6C
- Manual finalization does not prove reconciliation correctness
- [ADR-014](adr/ADR-014-commercial-period-lifecycle.md)

## Phase 8A — Deterministic rebuild + reconciliation reporting

- Flyway V12: `reconciliation_run` + `reconciliation_item` (immutable completed evidence)
- Usage Pipeline admin API: read / rebuild / compare / report from canonical `usage_ledger`
- Observed vs commercially applicable expected totals (quarantine visible, not silently applied)
- SUM / COUNT / MAX rebuild matches live aggregation semantics; no aggregate/quota repair
- One active `RUNNING` run per period (partial unique index); FINALIZED blocked while RUNNING
- MATCH does not auto-finalize
- [ADR-015](adr/ADR-015-reconciliation-and-deterministic-rebuild.md)

## Phase 8B — Explicit UsageAdjustment (current)

- Flyway V13: `usage_adjustment` (append-oriented) + reconciliation item adjusted/unresolved counts
- `APPLY_QUARANTINED_USAGE` only; contribution derived from canonical ledger + meter semantics
- Allowed for `RECONCILING` / `FINALIZED` against COMPLETED reconciliation evidence
- Atomic aggregate correction; `quota_state` untouched; no Kafka republish
- Rebuild includes applied adjustments; old reconciliation runs remain immutable
- [ADR-016](adr/ADR-016-explicit-usage-adjustments.md)

## Phase 7B — Tenancy hardening (optional RLS revisit)

- Revisit PostgreSQL RLS with proven session handling ([ADR-006](adr/ADR-006-postgresql-rls.md))
- Expand audit of commercial-state access as needed

## Phase 9+ — Operability

- Observability (OpenTelemetry / Prometheus as needed), deploy packaging, load/failure drills
- No “production-ready” claim without recorded evidence

## Explicit deferrals

K8s/AWS/Terraform until operability needs them · Redis/Mongo/ES/GraphQL/mesh without measured need · Cognito until production IdP cutover · Schema Registry/Avro until demonstrated · AI/LLM · Frontend
