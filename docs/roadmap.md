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

## Phase 8B — Explicit UsageAdjustment

- Flyway V13: `usage_adjustment` (append-oriented) + reconciliation item adjusted/unresolved counts
- `APPLY_QUARANTINED_USAGE` only; contribution derived from canonical ledger + meter semantics
- Allowed for `RECONCILING` / `FINALIZED` against COMPLETED reconciliation evidence
- Atomic aggregate correction; `quota_state` untouched; no Kafka republish
- Rebuild includes applied adjustments; old reconciliation runs remain immutable
- [ADR-016](adr/ADR-016-explicit-usage-adjustments.md)

## Phase 9A — Structured observability

- Micrometer + Prometheus + OpenTelemetry (W3C) across all three workloads
- Structured logs with `correlationId` / `traceId` / `spanId`; correlation remains distinct from trace id
- HTTP → outbox envelope → Kafka `traceparent` → consumer MDC continuation
- Bounded-cardinality business metrics (outbox, usage processing, quota, entitlement, commercial period, reconciliation, adjustment)
- Actuator `health` / `info` / `prometheus`; Usage Pipeline readiness is PostgreSQL, not Kafka
- Local Prometheus + OTel Collector in Docker Compose
- [ADR-017](adr/ADR-017-observability-architecture.md)

## Phase 9B — Operational dashboards and alerting

- Grafana in Compose with provisioned Prometheus datasource and three dashboards
- Prometheus recording + alert rules (demo thresholds); runbooks; SLO-style indicators (not production SLOs)
- No Alertmanager notification routing, no automatic remediation
- [ADR-018](adr/ADR-018-operational-dashboards-and-alerting.md)

## Phase 10 — Resilience engineering and failure recovery

- Deterministic Testcontainers pause/unpause for Kafka and PostgreSQL (no Resilience4j/Toxiproxy)
- Flagship proofs: Kafka outage + backlog drain, ACK-before-PUBLISHED duplicate window, consumer commit/offset gap, PostgreSQL unavailability, poison/DLQ isolation, delayed delivery across CommercialPeriod finalization
- HTTP 202 remains durable PostgreSQL acceptance; Kafka is asynchronous delivery
- [ADR-019](adr/ADR-019-resilience-and-failure-recovery.md), [failure matrix](resilience/failure-matrix.md)

## Phase 11 — Performance engineering

- Repeatable local Gatling laboratory (`performance/`): entitlement check, `/usage/events` HTTP 202, `/usage/consume`, ingest burst/drain
- Profiles: warm-up (excluded from headlines), baseline, ramp, sustained, burst, quota contention
- Cached Keycloak token; JFR + EXPLAIN ANALYZE + existing Micrometer/Grafana; no CI latency gates
- Optimizations only with before/after evidence; no speculative indexes required
- [ADR-020](adr/ADR-020-performance-engineering-and-benchmark-methodology.md), [performance lab](performance/README.md)

## Phase 12 — Kubernetes packaging and operability

- Multi-stage container images (Java 21, non-root) for all three workloads
- kind cluster + Helm chart (`infrastructure/kubernetes/`)
- In-cluster local PostgreSQL (PVC), Kafka, Keycloak; apps externalized via ConfigMap/Secret
- Probes: liveness/startup without Kafka; readiness PostgreSQL-only on Usage Pipeline
- Replicas: control-plane 1, entitlement-runtime 2, usage-pipeline 2+
- Live drills: Kafka/PostgreSQL outage, pending-outbox pod restart, scale, rollout
- [ADR-021](adr/ADR-021-kubernetes-packaging-and-operability.md), [kubernetes docs](kubernetes/README.md)

## Phase 13 — AWS architecture and Terraform

- Terraform for a single `dev` environment: VPC, EKS, RDS PostgreSQL 16, provisioned MSK, ECR (three images), IAM / EKS Pod Identity, Secrets Manager containers
- Helm reuse via `values-aws.yaml` (no second chart; in-cluster Postgres/Kafka/Keycloak disabled)
- Control Plane remains Flyway owner; no Terraform migrations or topic management
- Cost-aware defaults: one NAT Gateway, single-AZ RDS, two MSK brokers
- Evidence: Terraform fmt/validate (and plan if credentials exist). **Not** a live AWS apply
- [ADR-022](adr/ADR-022-aws-deployment-architecture-and-terraform.md), [AWS docs](aws/README.md)

## Phase 14 — CI/CD, supply-chain security, and gated delivery

- GitHub Actions: PR Maven verify, Terraform fmt/validate, Helm/Compose checks
- Security: CodeQL, dependency review, Trivy IaC/images; Dependabot weekly (no auto-merge)
- Immutable images (git SHA), optional ECR publish via OIDC
- Terraform plan ≠ apply; apply and Helm behind `workflow_dispatch` + environment `dev`
- AWS Kafka SASL_SSL/SCRAM configuration for Usage Pipeline; local PLAINTEXT preserved
- Secrets Manager → Kubernetes Secret at deploy time; ALB controller install as platform step
- Evidence: local static validation plus first GitHub-hosted runs on `24975ee` (mixed; see [CI/CD evidence](cicd/evidence.md)). **Not** a live AWS apply or ECR push
- [ADR-023](adr/ADR-023-github-actions-ci-cd-and-supply-chain-security.md), [CI/CD docs](cicd/README.md)

## Phase 15 — Portfolio hardening (current)

- Documentation consistency, evidence indexing, demo reproducibility, evaluator navigation
- No new runtime service, API family, or cost-bearing AWS apply
- [Docs index](README.md), [reviewer guide](portfolio/reviewer-guide.md), [engineering evidence](evidence/engineering-evidence.md)

## Completed major phases (checklist)

0 Engineering charter · 1 Control Plane · 2 Security / multi-tenancy · 3 Entitlement Runtime · 4 Kafka usage ingestion · 5A Durable ingestion / outbox · 5B Consumer inbox / ledger · 6A Metering / lifetime aggregation · 6B Event-time windows · 6C Strict quota enforcement · 7 Commercial period lifecycle · 8A Reconciliation · 8B Usage adjustments · 9A Observability · 9B Dashboards / alerts / runbooks · 10 Resilience · 11 Performance · 12 Kubernetes · 13 AWS/Terraform · 14 CI/CD · 15 Portfolio hardening

## Phase 7B — Tenancy hardening (deferred)

- Revisit PostgreSQL RLS with proven session handling ([ADR-006](adr/ADR-006-postgresql-rls.md))
- **Not implemented.** Application JWT tenancy remains the v1 control. Do not infer RLS from Phase 15 docs.

## Explicit deferrals

PostgreSQL RLS (Phase 7B) · live AWS deployment unless explicitly approved · live GitHub/AWS pipeline execution unless recorded · Redis/Mongo/ES/GraphQL/mesh without measured need · Cognito until an explicit IdP cutover · Schema Registry/Avro until demonstrated · AI/LLM · Frontend · destructive DB DR · DLQ destination outage drill · autoscaling proof
