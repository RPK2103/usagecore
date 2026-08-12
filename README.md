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

## Phase 2A status

Control Plane APIs under `/api/v1` require a validated JWT (OAuth2 resource server).

- **Tenant authority** comes from the validated JWT `tenant_id` claim (plus roles).
- Do **not** treat `X-Tenant-ID`, URL/query parameters, or request-body `tenantId` as authorization evidence. A body may include `tenantId` where administratively required; the server compares it to the authenticated context.
- Keycloak is the **local/demo** identity provider. Production target is Cognito later.
- PostgreSQL RLS is intentionally deferred for v1 ([ADR-006](docs/adr/ADR-006-postgresql-rls.md)).

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

Defaults when running the app:

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
| `globex-billing` | BILLING_OPERATOR | Globex placeholder |

After creating real Tenant rows, update Keycloak user attributes so `tenant_id` matches the database UUID (or create tenants with those fixed UUIDs via admin tooling). Automated tests mint JWTs and do not require a live Keycloak.

Obtain a token (password grant, local only):

```bash
curl -s -X POST "http://localhost:8081/realms/usagecore/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=usagecore-control-plane" \
  -d "username=platform-admin" \
  -d "password=platform-admin" \
  -d "grant_type=password"
```

## Run the control-plane application

```bash
# Windows
.\mvnw.cmd -pl applications/control-plane spring-boot:run

# Unix
./mvnw -pl applications/control-plane spring-boot:run
```

Health (unauthenticated): `http://localhost:8080/actuator/health`

## Authenticated local demo (curl)

Base URL: `http://localhost:8080/api/v1`

Set `TOKEN` from the Keycloak token response `access_token`, then:

```bash
# 1. Tenant (PLATFORM_ADMIN)
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantKey":"acme","displayName":"Acme Corp"}'

# 2. Product
curl -s -X POST http://localhost:8080/api/v1/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productKey":"datapilot","name":"DataPilot"}'
```

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

- Entitlement-runtime application / check endpoint
- Kafka / event streaming
- Cognito / AWS / Kubernetes
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
