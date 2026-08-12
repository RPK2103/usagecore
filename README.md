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

## Phase 0 status

Repository foundation and architecture decisions only. No application services, schema, or infrastructure code yet.

See:

- [docs/architecture/system-overview.md](docs/architecture/system-overview.md)
- [docs/architecture/domain-model.md](docs/architecture/domain-model.md)
- [docs/architecture/initial-er-model.md](docs/architecture/initial-er-model.md)
- [docs/roadmap.md](docs/roadmap.md)
- [docs/adr/](docs/adr/)

## Non-goals (current)

- Kafka / event streaming (deferred)
- Kubernetes, AWS, Terraform
- Redis, MongoDB, Elasticsearch, GraphQL, service mesh
- AI / LLM components
- Frontend UI
