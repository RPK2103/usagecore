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
| Architecture | Pragmatic hexagonal / clean |
| Auth (local) | Keycloak (OIDC) — **development only** |
| Auth (production target) | Cognito later (not configured in this phase) |

## Workloads

Long-term modules (build only what the current milestone needs):

1. **control-plane** — tenants, products, features, plans, contracts, activation
2. **entitlement-runtime** — evaluate entitlements against activated contract state
3. **usage-pipeline** — ingest, aggregate, reconcile usage (Kafka only after entitlement runtime foundation)

## Phase 3 status

Two independently deployable applications share the authoritative PostgreSQL commercial schema:

| Application | Port (default) | Flyway in process |
| --- | --- | --- |
| Control Plane | `8080` | **Enabled** — production schema migration owner |
| Entitlement Runtime | `8082` | **Disabled** by default — must not mutate shared production schema on every replica startup |

Flyway SQL lives once in [`libraries/database-migrations`](libraries/database-migrations/README.md) (V1–V5). Integration tests for either app may enable Flyway against a fresh Testcontainers database.

Entitlement Runtime:

- `POST /api/v1/entitlements/check` — authenticated commercial decision (`ALLOW` / `DENY` / `ALLOW_WITH_LIMIT`)
- Tenant identity comes **only** from the validated JWT `tenant_id` claim (never from the request body)
- Reads activated `ContractVersion` entitlement snapshots via JDBC (not live PlanFeature)
- Persists append-only `entitlement_decision` evidence
- **No** remainingQuota / consumedUnits / Kafka / Redis yet — see [ADR-007](docs/adr/ADR-007-entitlement-runtime-read-architecture.md)

Control Plane APIs under `/api/v1` still require a validated JWT. PostgreSQL RLS remains deferred ([ADR-006](docs/adr/ADR-006-postgresql-rls.md)).

See:

- [docs/architecture/system-overview.md](docs/architecture/system-overview.md)
- [docs/architecture/domain-model.md](docs/architecture/domain-model.md)
- [docs/architecture/initial-er-model.md](docs/architecture/initial-er-model.md)
- [docs/roadmap.md](docs/roadmap.md)
- [docs/adr/](docs/adr/)

## Prerequisites

- Java 21 JDK
- Docker (for local PostgreSQL, Keycloak, and Testcontainers)
- Maven Wrapper (included; no system Maven required)

## Local PostgreSQL + Keycloak

Credentials in Compose are **local development only**, not for production.

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

| Service | URL / port |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Keycloak | `http://localhost:8081` (admin / admin) |
| Realm | `usagecore` |

Defaults when running apps:

| Variable | Default |
| --- | --- |
| `USAGECORE_DB_URL` | `jdbc:postgresql://localhost:5432/usagecore` |
| `USAGECORE_DB_USERNAME` | `usagecore` |
| `USAGECORE_DB_PASSWORD` | `usagecore` |
| `USAGECORE_JWK_SET_URI` | `http://localhost:8081/realms/usagecore/protocol/openid-connect/certs` |

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
  -d "username=platform-admin" \
  -d "password=platform-admin" \
  -d "grant_type=password"
```

## Run applications

```bash
# Windows — Control Plane (owns Flyway migrations)
.\mvnw.cmd -pl applications/control-plane -am spring-boot:run

# Windows — Entitlement Runtime (Flyway disabled; schema must already exist)
.\mvnw.cmd -pl applications/entitlement-runtime -am spring-boot:run

# Unix
./mvnw -pl applications/control-plane -am spring-boot:run
./mvnw -pl applications/entitlement-runtime -am spring-boot:run
```

| App | Health |
| --- | --- |
| Control Plane | `http://localhost:8080/actuator/health` |
| Entitlement Runtime | `http://localhost:8082/actuator/health` |

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

`configuredLimit` is contractual configuration only. Remaining quota arrives in a later metering phase.

Optional correlation header (not authentication): `X-Correlation-Id`.

## Validation

```bash
# Windows
.\mvnw.cmd clean verify

# Unix
./mvnw clean verify
```

Requires Docker available for Testcontainers PostgreSQL tests.

```bash
docker compose -f infrastructure/docker/docker-compose.yml config
```

## Non-goals (current)

- Kafka / event streaming / usage metering / remaining quota
- Cognito / AWS / Kubernetes
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
