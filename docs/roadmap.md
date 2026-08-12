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

## Phase 2A — Control Plane security (current foundation)

- Spring Security OAuth2 resource server (JWT) on `/api/v1`
- Tenant context from validated `tenant_id` claim; RBAC roles
- Security audit evidence; RLS deferred ([ADR-006](adr/ADR-006-postgresql-rls.md))

## Phase 2B — Entitlement runtime

- Evaluate entitlements from activated contract evidence
- Read path optimized for check latency (still PostgreSQL SoT)
- No Kafka yet

## Phase 3 — Usage pipeline foundation

- Introduce Kafka for at-least-once usage ingestion
- Aggregation and reconciliation against entitlements
- Idempotency / dedup strategies documented honestly (at-least-once)

## Phase 4 — Tenancy hardening (optional RLS revisit)

- Revisit PostgreSQL RLS with proven session handling ([ADR-006](adr/ADR-006-postgresql-rls.md))
- Expand audit of commercial-state access as needed

## Phase 5 — Operability

- Observability, deploy packaging, load/failure drills as evidenced by tests/runs
- No “production-ready” claim without recorded evidence

## Explicit deferrals

Kafka before Phase 3 · K8s/AWS/Terraform until operability needs them · Redis/Mongo/ES/GraphQL/mesh without measured need · Cognito until production IdP cutover · AI/LLM · Frontend
