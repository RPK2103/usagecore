# UsageCore

Multi-tenant B2B SaaS platform for entitlement, contract versioning, usage metering, and reconciliation.

Java backend portfolio project. No frontend in this repository.

## Stack (approved)

| Concern | Choice |
| --- | --- |
| Language / runtime | Java 21 |
| Framework | Spring Boot 3 |
| Build | Maven |
| Transactional store | PostgreSQL |
| Async usage transport | Kafka (JSON envelopes; at-least-once) |
| Architecture | Pragmatic hexagonal / clean |
| Auth (local) | Keycloak (OIDC) — **development only** |
| Auth (production target) | Cognito later (not configured in this phase) |

## Workloads

| Workload | Module | Default port |
| --- | --- | --- |
| control-plane | `applications/control-plane` | `8080` |
| entitlement-runtime | `applications/entitlement-runtime` | `8082` |
| usage-pipeline | `applications/usage-pipeline` | `8083` |

Shared libraries:

- [`libraries/database-migrations`](libraries/database-migrations/README.md) — Flyway SQL (Control Plane owns production migrations)
- [`libraries/event-contracts`](libraries/event-contracts) — versioned Kafka transport envelopes only

## Phase 11 status

Phase 10 (resilience failure-recovery) is complete. **Phase 11** adds a **local performance laboratory** (Gatling in `performance/`, seed/verify/EXPLAIN/JFR docs). Results are machine-specific and are **not** production capacity, production SLOs, or hardware-independent TPS claims.

See [ADR-020](docs/adr/ADR-020-performance-engineering-and-benchmark-methodology.md) and [docs/performance/](docs/performance/README.md).

## Phase 12 status

**Phase 12** packages the three workloads for **local Kubernetes (kind + Helm)**: container images, probes, ConfigMap/Secret config, multi-replica usage-pipeline validation, and operability failure drills. This is **not** AWS/EKS, production HA, or disaster recovery.

```powershell
.\infrastructure\kubernetes\scripts\create-cluster.ps1
.\infrastructure\kubernetes\scripts\build-images.ps1 -Tag phase12
.\infrastructure\kubernetes\scripts\load-images.ps1 -Tag phase12
.\infrastructure\kubernetes\scripts\deploy.ps1 -Tag phase12
.\infrastructure\kubernetes\scripts\smoke.ps1
```

See [ADR-021](docs/adr/ADR-021-kubernetes-packaging-and-operability.md) and [docs/kubernetes/](docs/kubernetes/README.md).

## Phase 10 status

Phase 9B (Grafana + Prometheus alerts + runbooks) is complete. **Phase 10** proves selected failure windows: Kafka publication outage/recovery, outbox ACK-before-PUBLISHED duplicates, consumer commit/offset gap, PostgreSQL unavailability, poison/DLQ isolation, and delayed delivery across CommercialPeriod finalization.

UsageCore is designed for **at-least-once** delivery and duplicate-safe recovery across those windows. It does **not** claim exactly-once transport, zero data loss, automatic disaster recovery, or production HA.

See [ADR-019](docs/adr/ADR-019-resilience-and-failure-recovery.md) and the [failure matrix](docs/resilience/failure-matrix.md).

## Phase 9B observability (retained)

Phase 8B (explicit UsageAdjustment) and **Phase 9A** (metrics/traces/logs) are complete. **Phase 9B** adds a local Grafana + Prometheus alerting loop (observe → visualize → detect → investigate). Dashboards are not commercial correctness authority. Alert thresholds are demo/engineering defaults, not production SLOs. There is no automatic remediation.

Three independently deployable applications:

| Application | Responsibility |
| --- | --- |
| Control Plane | Catalog / commercial configuration (including MeterDefinition → Feature) + **CommercialPeriod** lifecycle; production Flyway owner; blocks FINALIZED while reconciliation is RUNNING |
| Entitlement Runtime | Authenticated **read-only** entitlement checks against activated snapshots ([ADR-007](docs/adr/ADR-007-entitlement-runtime-read-architecture.md)) |
| Usage Pipeline | Durable ingestion + outbox + idempotent consumer ledger/aggregates + synchronous quota consume + commercial-period enforcement + reconciliation rebuild/compare/report + explicit UsageAdjustment + observability + Phase 10 failure-recovery evidence ([ADR-008](docs/adr/ADR-008-kafka-usage-topology.md)–[ADR-019](docs/adr/ADR-019-resilience-and-failure-recovery.md)) |

Usage Pipeline Phase 8B:

- `APPLY_QUARANTINED_USAGE` only — contribution derived from `usage_ledger` + meter semantics (no caller-supplied quantity/totals)
- Allowed on `RECONCILING` / `FINALIZED` against a **COMPLETED** reconciliation run
- Immutable `usage_adjustment` evidence; `usage_ledger` and `commercial_usage_exception` are not rewritten
- Atomic PostgreSQL update of lifetime + window aggregates; `quota_state` is not mutated
- New reconciliation run verifies the result; old runs stay immutable
- **No** automatic repair, Kafka historical replay, or unfinalize

HTTP 202 on `/events` means durably accepted for asynchronous processing — not that quotas/billing changed.

## Prerequisites

- Java 21 JDK
- Docker (for local PostgreSQL, Keycloak, Kafka, Prometheus, Grafana, OTel Collector, and Testcontainers)
- Maven Wrapper (included; no system Maven required)

## Local PostgreSQL + Keycloak + Kafka + observability scrapers

Credentials in Compose are **local development only**, not for production.

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

| Service | URL / port |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Keycloak | `http://localhost:8081` (admin / admin) |
| Kafka (KRaft single broker) | `localhost:9092` |
| Prometheus | `http://localhost:9090` (scrapes host apps via `host.docker.internal`) |
| Grafana | `http://localhost:3000` (local/demo `admin` / `admin`; Prometheus datasource provisioned) |
| OTel Collector | OTLP HTTP `localhost:4318` (debug exporter; no Tempo/trace UI) |
| Realm | `usagecore` |

Defaults when running apps:

| Variable | Default |
| --- | --- |
| `USAGECORE_DB_URL` | `jdbc:postgresql://localhost:5432/usagecore` |
| `USAGECORE_DB_USERNAME` | `usagecore` |
| `USAGECORE_DB_PASSWORD` | `usagecore` |
| `USAGECORE_JWK_SET_URI` | `http://localhost:8081/realms/usagecore/protocol/openid-connect/certs` |
| `USAGECORE_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `USAGECORE_OTLP_ENABLED` | `false` (set `true` with `SPRING_PROFILES_ACTIVE=local`) |
| `USAGECORE_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` |

### Demo users (local/demo-only)

Passwords match usernames unless noted. Tenant-bound users ship with **placeholder** `tenant_id` attributes:

| User | Role | `tenant_id` claim |
| --- | --- | --- |
| `platform-admin` | PLATFORM_ADMIN | (none) |
| `acme-contract-manager` | CONTRACT_MANAGER | `11111111-1111-1111-1111-111111111111` |
| `globex-contract-manager` | CONTRACT_MANAGER | `22222222-2222-2222-2222-222222222222` |
| `acme-tenant-admin` | TENANT_ADMIN | Acme placeholder |
| `acme-auditor` | AUDITOR | Acme placeholder |
| `acme-developer` | DEVELOPER | Acme placeholder |
| `globex-developer` | DEVELOPER | Globex placeholder |
| `globex-billing` | BILLING_OPERATOR | Globex placeholder |

**Local-demo automation item:** placeholder Keycloak `tenant_id` values do not automatically match Tenant UUIDs created via Control Plane APIs. Do **not** weaken JWT tenant rules to paper over this. Align Keycloak attributes (or create tenants with those fixed UUIDs) before a live Keycloak→runtime demo.

Automated tests mint JWTs and do not require a live Keycloak.

### Local M2M client (demo only)

| Client | Secret | Notes |
| --- | --- | --- |
| `usagecore-datapilot-m2m-demo` | `datapilot-m2m-demo-secret-local-only` | Client-credentials demo; hardcoded Acme placeholder `tenant_id`; **not production** |

Obtain a user token (password grant, local only):

```bash
curl -s -X POST "http://localhost:8081/realms/usagecore/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=usagecore-control-plane" \
  -d "username=acme-developer" \
  -d "password=acme-developer" \
  -d "grant_type=password"
```

## Run applications

```bash
# Windows — Control Plane (owns Flyway migrations)
.\mvnw.cmd -pl applications/control-plane -am spring-boot:run

# Windows — Entitlement Runtime (Flyway disabled; schema must already exist)
.\mvnw.cmd -pl applications/entitlement-runtime -am spring-boot:run

# Windows — Usage Pipeline (Kafka required)
.\mvnw.cmd -pl applications/usage-pipeline -am spring-boot:run

# Unix
./mvnw -pl applications/control-plane -am spring-boot:run
./mvnw -pl applications/entitlement-runtime -am spring-boot:run
./mvnw -pl applications/usage-pipeline -am spring-boot:run
```

| App | Health | Prometheus |
| --- | --- | --- |
| Control Plane | `http://localhost:8080/actuator/health` | `http://localhost:8080/actuator/prometheus` |
| Entitlement Runtime | `http://localhost:8082/actuator/health` | `http://localhost:8082/actuator/prometheus` |
| Usage Pipeline | `http://localhost:8083/actuator/health` | `http://localhost:8083/actuator/prometheus` |

Usage Pipeline readiness depends on PostgreSQL, not Kafka. Observability: [ADR-017](docs/adr/ADR-017-observability-architecture.md), [ADR-018](docs/adr/ADR-018-operational-dashboards-and-alerting.md), [metrics](docs/observability/metrics.md), [alerts](docs/observability/alerts.md), [local setup](docs/observability/local-observability.md), [runbooks](docs/observability/runbooks/). Failure recovery: [ADR-019](docs/adr/ADR-019-resilience-and-failure-recovery.md), [failure matrix](docs/resilience/failure-matrix.md). Performance lab: [ADR-020](docs/adr/ADR-020-performance-engineering-and-benchmark-methodology.md), [docs/performance](docs/performance/README.md).

## Authenticated local demos (curl)

### Control Plane

Base URL: `http://localhost:8080/api/v1`

```bash
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantKey":"acme","displayName":"Acme Corp"}'
```

### Entitlement Runtime

Base URL: `http://localhost:8082/api/v1`

Requires a **tenant-bound** JWT (`DEVELOPER`, `TENANT_ADMIN`, or `CONTRACT_MANAGER`). `tenantId` must **not** appear in the body.

```bash
curl -s -X POST http://localhost:8082/api/v1/entitlements/check \
  -H "Authorization: Bearer $DEVELOPER_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: demo-1" \
  -d '{"productKey":"datapilot-cloud","featureKey":"scheduled_exports","requestedUnits":1}'
```

### Usage Pipeline

Base URL: `http://localhost:8083/api/v1`

Requires a **tenant-bound** `DEVELOPER` JWT (or M2M client with that role). `tenantId` must **not** appear in the body — tenant comes only from the JWT.

```bash
curl -s -X POST http://localhost:8083/api/v1/usage/events \
  -H "Authorization: Bearer $DEVELOPER_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: export-corr-1" \
  -d '{
    "productKey":"datapilot-cloud",
    "meterKey":"scheduled_export",
    "quantity":1,
    "occurredAt":"2026-08-12T14:30:00Z",
    "idempotencyKey":"export-job-174"
  }'
```

Expected: HTTP **202** with `status=ACCEPTED` after durable PostgreSQL acceptance (ingestion + outbox). Kafka publication is asynchronous. This does **not** mean usage totals, quotas, or billing state changed.

Optional correlation header (not authentication): `X-Correlation-Id`.

## Validation

```bash
# Windows
.\mvnw.cmd clean verify

# Unix
./mvnw clean verify
```

Requires Docker available for Testcontainers PostgreSQL and Kafka tests.

```bash
docker compose -f infrastructure/docker/docker-compose.yml config
```

Gatling load tests are **not** part of `clean verify`. Explicit lab commands: [docs/performance](docs/performance/README.md).

## Non-goals (current)

- Pricing, invoices, credits, billing exports
- Compensating/undo UsageAdjustment, automatic exception application, quota repair, Kafka historical replay
- Automated commercial-period schedulers / tenant-specific timezones
- Kafka Streams / Schema Registry / Avro
- Cognito / AWS production deployment
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
- Production notification routing / PagerDuty / automatic incident remediation
- Production-ready / exactly-once / production SLO / production HA claims
- Claims that Phase 7 finalization proves reconciled aggregate correctness
- Claims that Phase 10 proves disaster recovery or zero message loss under arbitrary infrastructure destruction
- Claims that local Gatling numbers are production capacity or hardware-independent TPS
