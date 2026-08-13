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

## Phase 6B — Event-time windows + late-event semantics (current)

- `MeterDefinition.aggregationWindow` (`MONTHLY` / `DAILY`) — UTC half-open intervals
- Derived `usage_window_aggregate` + `usage_ledger.is_late` — Flyway V9
- Window assignment from `occurredAt`; late events accepted and update historical windows
- Semantic meter fields immutable after create ([ADR-012](adr/ADR-012-event-time-and-windowed-metering.md))
- Kafka Streams re-evaluated and still deferred
- No commercial-period finalization, quota, billing, or adjustments yet

## Phase 6C+ — Quota (later)

- Remaining quota against entitlements (as evidenced)

## Phase 7 — Tenancy hardening (optional RLS revisit)

- Revisit PostgreSQL RLS with proven session handling ([ADR-006](adr/ADR-006-postgresql-rls.md))
- Expand audit of commercial-state access as needed

## Phase 8+ — Operability

- Observability (OpenTelemetry / Prometheus as needed), deploy packaging, load/failure drills
- No “production-ready” claim without recorded evidence

## Explicit deferrals

K8s/AWS/Terraform until operability needs them · Redis/Mongo/ES/GraphQL/mesh without measured need · Cognito until production IdP cutover · Schema Registry/Avro until demonstrated · AI/LLM · Frontend
