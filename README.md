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

## Phase 6B status

Three independently deployable applications:

| Application | Responsibility |
| --- | --- |
| Control Plane | Catalog / commercial configuration (including MeterDefinition); production Flyway owner |
| Entitlement Runtime | Authenticated entitlement checks against activated snapshots ([ADR-007](docs/adr/ADR-007-entitlement-runtime-read-architecture.md)) |
| Usage Pipeline | Durable ingestion + outbox + idempotent consumer ledger + lifetime + event-time window aggregation ([ADR-008](docs/adr/ADR-008-kafka-usage-topology.md), [ADR-009](docs/adr/ADR-009-transactional-outbox-ingestion-idempotency.md), [ADR-010](docs/adr/ADR-010-consumer-inbox-and-idempotent-processing.md), [ADR-011](docs/adr/ADR-011-metering-and-aggregation.md), [ADR-012](docs/adr/ADR-012-event-time-and-windowed-metering.md)) |

Usage Pipeline Phase 6B:

- `POST /api/v1/usage/events` → **202** after PostgreSQL commit of `usage_ingestion` + `outbox_event`
- Asynchronous outbox publisher → topic `usagecore.usage.received.v1` (at-least-once)
- Consumer inbox (`processed_event`) + canonical `usage_ledger` + lifetime `usage_aggregate` + event-time `usage_window_aggregate`
- Aggregation from Control Plane `MeterDefinition`: `SUM` / `COUNT` / `MAX` with UTC `MONTHLY` / `DAILY` windows
- Window ownership uses `occurredAt`; late events are accepted, marked `is_late`, and update historical windows
- Duplicate Kafka redelivery → successful no-op (one ledger effect, one lifetime + one window contribution)
- Bounded retry; poison/non-retryable (including unknown meter) → `usagecore.usage.received.v1.dlq`
- Reads: `GET /api/v1/usage/aggregates/{productKey}/{meterKey}` and `.../windows/current` (tenant from JWT)
- **No** commercial-period finalization, Streams, quota, or pricing yet

HTTP 202 means durably accepted for asynchronous processing — not that quotas/billing changed.

## Prerequisites

- Java 21 JDK
- Docker (for local PostgreSQL, Keycloak, Kafka, and Testcontainers)
- Maven Wrapper (included; no system Maven required)

## Local PostgreSQL + Keycloak + Kafka

Credentials in Compose are **local development only**, not for production.

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

| Service | URL / port |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Keycloak | `http://localhost:8081` (admin / admin) |
| Kafka (KRaft single broker) | `localhost:9092` |
| Realm | `usagecore` |

Defaults when running apps:

| Variable | Default |
| --- | --- |
| `USAGECORE_DB_URL` | `jdbc:postgresql://localhost:5432/usagecore` |
| `USAGECORE_DB_USERNAME` | `usagecore` |
| `USAGECORE_DB_PASSWORD` | `usagecore` |
| `USAGECORE_JWK_SET_URI` | `http://localhost:8081/realms/usagecore/protocol/openid-connect/certs` |
| `USAGECORE_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

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

| App | Health |
| --- | --- |
| Control Plane | `http://localhost:8080/actuator/health` |
| Entitlement Runtime | `http://localhost:8082/actuator/health` |
| Usage Pipeline | `http://localhost:8083/actuator/health` |

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

## Non-goals (current)

- Remaining quota, commercial-period finalization (`FINALIZED`), pricing, invoices
- `UsageAdjustment` / late-event rejection
- Kafka Streams / Schema Registry / Avro
- Cognito / AWS / Kubernetes
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
- Production-ready / exactly-once claims
