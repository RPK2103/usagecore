# UsageCore

A multi-tenant B2B SaaS **infrastructure** platform for commercial entitlements, usage metering, strict quota enforcement, contract-version history, commercial period finalization, reconciliation, and failure-safe event processing.

Java backend / distributed-systems portfolio. No frontend. Not a billing platform, not exactly-once, not a live AWS deployment, not production-ready by adjective.

**Five-minute tour:** [docs/portfolio/reviewer-guide.md](docs/portfolio/reviewer-guide.md)
**Docs index:** [docs/README.md](docs/README.md)

## What it models

A product like **DataPilot Cloud** sells features (API access, scheduled exports) to tenants (**Acme**, **Globex**). Contracts version over time. Usage is metered asynchronously. Some actions require **strict quota** that must not over-admit under concurrency. Commercial periods finalize. Delayed events must not silently rewrite history. Failures duplicate **transport**, not **business effects**.

## Why it is technically interesting

- Explicit **source-of-truth** split: ledger vs aggregates vs quota vs outbox/inbox ([table](docs/architecture/source-of-truth.md)).
- **Transactional outbox** + **consumer inbox** instead of unsafe dual writes or “exactly-once” claims.
- **PostgreSQL** as quota concurrency authority across replicas — not a JVM lock.
- **Commercial finalization** that preserves delayed ledger evidence and quarantines instead of mutating finalized aggregates.
- **Reconciliation reports**; **adjustments** are explicit and immutable.
- Evidence is labeled: tests, kind drills, local Gatling, Terraform validate, workflow configuration.

## Architecture

Exactly three deployable workloads:

| Workload | Responsibility | Port |
| --- | --- | --- |
| **Control Plane** | Catalogue, contracts, commercial periods; production Flyway owner | `8080` |
| **Entitlement Runtime** | Authenticated **read-only** entitlement checks against activated snapshots | `8082` |
| **Usage Pipeline** | Durable ingest, outbox, Kafka consume, ledger/aggregates, strict consume, reconciliation, adjustments | `8083` |

Not services:

| Path | What it is |
| --- | --- |
| `performance/` | Engineering lab (Gatling), not a runtime |
| `infrastructure/terraform/` | Infrastructure **code**, not a running cloud |
| `libraries/*` | Flyway SQL and Kafka envelopes — not deployables |

Canonical diagram: [docs/architecture/diagrams.md](docs/architecture/diagrams.md). PostgreSQL is correctness authority. Kafka is at-least-once transport.

## Core guarantees (actually designed and tested)

1. Tenant identity from JWT only — never body/header authority.
2. Activated `ContractVersion` is immutable historical evidence; plans are templates.
3. `POST /usage/events` HTTP **202** = PostgreSQL accepted ingest+outbox — not consumer/Kafka/quota completion.
4. Duplicate Kafka delivery does not double-apply (`processed_event`).
5. Strict consume: limit 100, consumed 90, 20 concurrent × 1 → **10 accepted**, final **100**.
6. Delayed usage after FINALIZED can ledger + exception without rewriting aggregates.
7. Reconciliation does not auto-repair; adjustments are explicit.
8. Usage Pipeline readiness depends on PostgreSQL, not Kafka.

Details and caveats: [docs/evidence/engineering-evidence.md](docs/evidence/engineering-evidence.md).

## Flagship APIs (do not blur)

| API | Meaning |
| --- | --- |
| `POST /api/v1/entitlements/check` | Read-only commercial decision. Does **not** consume quota. |
| `POST /api/v1/usage/events` | Async metering. **202** = durable PostgreSQL acceptance. |
| `POST /api/v1/usage/consume` | Synchronous strict quota admission. HTTP 200 with `ACCEPTED` or `REJECTED`. |

Examples: [docs/demo/api-examples.md](docs/demo/api-examples.md).

## Stack (what is actually in the repo)

Java 21 · Spring Boot 3 · Maven · PostgreSQL · Flyway · Kafka (JSON) · Testcontainers · JUnit 5 · REST Assured · Mockito · ArchUnit · Micrometer · OpenTelemetry · Prometheus · Grafana · Gatling · JFR · Docker · Kubernetes (kind) · Helm · Terraform · AWS **architecture** · GitHub Actions · Keycloak (**local** IdP)

Not used (intentionally): Redis, Kafka Streams, MongoDB, Elasticsearch, GraphQL, service mesh, AI/LLM.

Local auth is Keycloak. Cloud target is an external OIDC issuer (Cognito is a possible later choice and is **not configured**).

## How to run (one local path)

Prerequisites: Java 21, Docker.

```powershell
docker compose -f infrastructure/docker/docker-compose.yml up -d

.\mvnw.cmd -pl applications/control-plane,applications/entitlement-runtime,applications/usage-pipeline -am install -DskipTests

java -jar applications/control-plane/target/control-plane-0.1.0-SNAPSHOT.jar
java -jar applications/entitlement-runtime/target/entitlement-runtime-0.1.0-SNAPSHOT.jar
java -jar applications/usage-pipeline/target/usage-pipeline-0.1.0-SNAPSHOT.jar

.\performance\scripts\seed.ps1
```

Unix: `./mvnw` and the same `java -jar` / Compose commands.

Do not use `.\mvnw.cmd -pl applications/<app> -am spring-boot:run` from the repo root (`-am` includes the parent POM, which has no main class).

Compose ports: PostgreSQL `5432`, Keycloak `8081`, Kafka `9092`, Prometheus `9090`, Grafana `3000` (local `admin`/`admin`), OTel `4318`. Compose credentials are **local development only**.

Demo users (password = username unless noted): `acme-developer`, `platform-admin`, `globex-developer`, … — [demo walkthrough](docs/demo/README.md). Acme JWT `tenant_id` is the placeholder `11111111-1111-1111-1111-111111111111`; the seeder uses that UUID on purpose.

## How to demo

Follow [docs/demo/README.md](docs/demo/README.md) (~10–15 minutes): token → entitlement check → usage 202 → SQL ledger → strict consume → point at existing resilience/performance/Kubernetes evidence.

## What was tested

`.\mvnw.cmd clean verify` — domain, REST, security, PostgreSQL/Kafka Testcontainers, concurrency, resilience seams, ArchUnit. Count is the Maven total after the run, not a slogan. Categories: [docs/evidence/test-strategy.md](docs/evidence/test-strategy.md).

Gatling is **not** part of `clean verify`.

## What was measured

Local Gatling on a documented laptop (2026-08-18). Separate paths for check / 202 ingest / consume. **No production optimization was justified.** Summary: [docs/performance/summary.md](docs/performance/summary.md). Not cloud capacity.

## What was not proven

| Area | Status |
| --- | --- |
| Kubernetes on **kind** | Live smoke + failure drills (Phase 12) |
| AWS EKS/RDS/MSK | Terraform **configuration validated**; not live-applied |
| GitHub Actions on GitHub | First `main` push of Phase 14: Terraform + Container succeeded; CI (mvnw mode) and Trivy IaC failed. See [docs/cicd/evidence.md](docs/cicd/evidence.md) |
| PostgreSQL RLS | **Deferred** (Phase 7B) |
| Disaster recovery / AZ failover / autoscaling | Deferred or unexecuted |

Full list: [docs/limitations.md](docs/limitations.md). Roadmap: [docs/roadmap.md](docs/roadmap.md).

## Validation (local, no AWS spend)

```powershell
.\mvnw.cmd clean verify
docker compose -f infrastructure/docker/docker-compose.yml config --quiet
```

Helm / Terraform (no apply):

```powershell
helm lint infrastructure/kubernetes/helm/usagecore
helm template usagecore infrastructure/kubernetes/helm/usagecore --namespace usagecore | Out-Null
helm template usagecore infrastructure/kubernetes/helm/usagecore --namespace usagecore -f infrastructure/kubernetes/helm/usagecore/values-aws.yaml | Out-Null

terraform -chdir=infrastructure/terraform/environments/dev init -backend=false
terraform fmt -check -recursive infrastructure/terraform
terraform -chdir=infrastructure/terraform/environments/dev validate
```

`terraform apply` is cost-bearing and requires explicit approval.

## Further reading

| Topic | Link |
| --- | --- |
| Interview stories | [docs/portfolio/interview-guide.md](docs/portfolio/interview-guide.md) |
| Evidence index | [docs/evidence/engineering-evidence.md](docs/evidence/engineering-evidence.md) |
| ADRs | [docs/adr/README.md](docs/adr/README.md) |
| Security | [docs/security/README.md](docs/security/README.md) |
| Kubernetes | [docs/kubernetes/README.md](docs/kubernetes/README.md) |
| AWS | [docs/aws/README.md](docs/aws/README.md) |
| CI/CD | [docs/cicd/README.md](docs/cicd/README.md) |
