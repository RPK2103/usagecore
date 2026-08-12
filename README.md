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

## Phase 1A status

Control-plane engineering foundation is in place (Spring Boot, PostgreSQL, Flyway, Actuator, tests). Domain business functionality is not implemented yet.

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

Health endpoint (only Actuator endpoint exposed): `http://localhost:8080/actuator/health`

## Validation

```bash
# Windows
.\mvnw.cmd clean verify

# Unix
./mvnw clean verify
```

Requires Docker available for Testcontainers PostgreSQL tests.

## Non-goals (current)

- Kafka / event streaming (deferred)
- Kubernetes, AWS, Terraform
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
