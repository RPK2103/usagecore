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

## Workloads

Long-term modules (build only what the current milestone needs):

1. **control-plane** — tenants, products, features, plans, contracts, activation
2. **entitlement-runtime** — evaluate entitlements against activated contract state
3. **usage-pipeline** — ingest, aggregate, reconcile usage (Kafka only after entitlement runtime foundation)

## Phase 1 status

Phase 1 control-plane is complete for local development:

- Domain + PostgreSQL persistence for catalogue and contracts
- Use-case application services
- REST API under `/api/v1` (no authentication yet)

**These endpoints are not protected.** Phase 2 introduces entitlement runtime; authentication/authorization is deferred. Do not treat Phase 1 as production-ready.

See:

- [docs/architecture/system-overview.md](docs/architecture/system-overview.md)
- [docs/architecture/domain-model.md](docs/architecture/domain-model.md)
- [docs/architecture/initial-er-model.md](docs/architecture/initial-er-model.md)
- [docs/roadmap.md](docs/roadmap.md)
- [docs/adr/](docs/adr/)

## Prerequisites

- Java 21 JDK
- Docker (for local PostgreSQL and Testcontainers)
- Maven Wrapper (included; no system Maven required)

## Local PostgreSQL

Credentials in Compose are **local development only**, not for production.

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

Defaults (override via environment when running the app):

| Variable | Default |
| --- | --- |
| `USAGECORE_DB_URL` | `jdbc:postgresql://localhost:5432/usagecore` |
| `USAGECORE_DB_USERNAME` | `usagecore` |
| `USAGECORE_DB_PASSWORD` | `usagecore` |

## Run the control-plane application

```bash
# Windows
.\mvnw.cmd -pl applications/control-plane spring-boot:run

# Unix
./mvnw -pl applications/control-plane spring-boot:run
```

Health: `http://localhost:8080/actuator/health`

## Phase 1 local demo (curl)

Base URL: `http://localhost:8080/api/v1`

```bash
# 1. Tenant
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantKey":"acme","displayName":"Acme Corp"}'

# 2. Product
curl -s -X POST http://localhost:8080/api/v1/products \
  -H "Content-Type: application/json" \
  -d '{"productKey":"datapilot","name":"DataPilot"}'

# 3. Feature (replace PRODUCT_ID)
curl -s -X POST http://localhost:8080/api/v1/products/PRODUCT_ID/features \
  -H "Content-Type: application/json" \
  -d '{"featureKey":"scheduled_exports","name":"Scheduled Exports"}'

# 4. Plan + LIMITED feature + publish
curl -s -X POST http://localhost:8080/api/v1/products/PRODUCT_ID/plans \
  -H "Content-Type: application/json" \
  -d '{"planKey":"enterprise","name":"Enterprise"}'

curl -s -X PUT http://localhost:8080/api/v1/products/PRODUCT_ID/plans/PLAN_ID/features/FEATURE_ID \
  -H "Content-Type: application/json" \
  -d '{"mode":"LIMITED","maxQuantity":1000000}'

curl -s -X POST http://localhost:8080/api/v1/products/PRODUCT_ID/plans/PLAN_ID/publish

# 5. Contract + version from plan + activate
curl -s -X POST http://localhost:8080/api/v1/contracts \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"TENANT_ID","productId":"PRODUCT_ID","contractKey":"acme-datapilot"}'

curl -s -X POST http://localhost:8080/api/v1/contracts/CONTRACT_ID/versions/from-plan \
  -H "Content-Type: application/json" \
  -d '{"planId":"PLAN_ID","effectiveFrom":"2026-01-01T00:00:00Z","effectiveUntil":"2026-06-01T00:00:00Z"}'

curl -s -X POST http://localhost:8080/api/v1/contracts/CONTRACT_ID/versions/1/activate

# 6. Temporal resolution
curl -s "http://localhost:8080/api/v1/contracts/CONTRACT_ID/effective-version?at=2026-05-31T23:59:59Z"
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

## Non-goals (current)

- Authentication / authorization (deferred)
- Kafka / event streaming (deferred)
- Kubernetes, AWS, Terraform
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
