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

## Phase 4 — Usage pipeline Kafka foundation (current)

- Independently deployable `applications/usage-pipeline`
- Authenticated `POST /api/v1/usage/events` → Kafka `usagecore.usage.received.v1`
- Shared `libraries/event-contracts` (transport envelopes only)
- Deterministic partition key `tenantId|productKey|meterKey`
- Testcontainers Kafka evidence; local KRaft Kafka in Docker Compose
- **Not yet:** outbox, inbox/dedup, aggregation, quota, Streams ([ADR-008](adr/ADR-008-kafka-usage-topology.md))

## Phase 5 — Distributed usage correctness

- Transactional outbox / consumer inbox
- Business idempotency via `idempotencyKey`
- Deliberate retry / poison-message behavior

## Phase 6 — Metering and aggregation

- MeterDefinition, usage aggregates, event-time windows / late events
- Remaining quota against entitlements (as evidenced)

## Phase 7 — Tenancy hardening (optional RLS revisit)

- Revisit PostgreSQL RLS with proven session handling ([ADR-006](adr/ADR-006-postgresql-rls.md))
- Expand audit of commercial-state access as needed

## Phase 8+ — Operability

- Observability (OpenTelemetry / Prometheus as needed), deploy packaging, load/failure drills
- No “production-ready” claim without recorded evidence

## Explicit deferrals

K8s/AWS/Terraform until operability needs them · Redis/Mongo/ES/GraphQL/mesh without measured need · Cognito until production IdP cutover · Schema Registry/Avro until demonstrated · AI/LLM · Frontend
