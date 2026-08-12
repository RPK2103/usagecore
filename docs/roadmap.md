# Roadmap

Milestones are sequential. Each builds only the workloads it needs.

## Phase 0 — Repository foundation (current)

- Cursor project rules
- Architecture docs and ADRs
- No application code, schema, or infra

## Phase 1 — Control-plane domain foundation

- Maven multi-module (or modular monolith) skeleton: domain + application + adapters as needed
- Domain: Tenant, Product, Feature, Plan, PlanFeature, Contract, ContractVersion, Entitlement
- PostgreSQL persistence for control-plane only
- Draft/activate contract version flows
- Tenant-scoped APIs (no frontend)

## Phase 2A — Control Plane security (foundation)

- Spring Security OAuth2 resource server (JWT) on `/api/v1`
- Tenant context from validated `tenant_id` claim; RBAC roles
- Security audit evidence; RLS deferred ([ADR-006](adr/ADR-006-postgresql-rls.md))

## Phase 3 — Entitlement runtime (current)

- Independently deployable `applications/entitlement-runtime`
- Authenticated `POST /api/v1/entitlements/check` against activated ContractVersion snapshots
- Shared PostgreSQL commercial SoT with no Control Plane compile-time dependency ([ADR-007](adr/ADR-007-entitlement-runtime-read-architecture.md))
- Shared Flyway resources in `libraries/database-migrations`; Control Plane owns production migrations
- No Kafka, metering, remaining quota, or Redis yet

## Phase 4 — Usage pipeline foundation

- Introduce Kafka for at-least-once usage ingestion
- Aggregation and reconciliation against entitlements
- Idempotency / dedup strategies documented honestly (at-least-once)

## Phase 5 — Tenancy hardening (optional RLS revisit)

- Revisit PostgreSQL RLS with proven session handling ([ADR-006](adr/ADR-006-postgresql-rls.md))
- Expand audit of commercial-state access as needed

## Phase 6 — Operability

- Observability, deploy packaging, load/failure drills as evidenced by tests/runs
- No “production-ready” claim without recorded evidence

## Explicit deferrals

Kafka before usage-pipeline · K8s/AWS/Terraform until operability needs them · Redis/Mongo/ES/GraphQL/mesh without measured need · Cognito until production IdP cutover · AI/LLM · Frontend · remainingQuota until metering phase
