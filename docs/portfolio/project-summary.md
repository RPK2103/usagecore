# Project summary

## One line

UsageCore is a multi-tenant B2B SaaS **infrastructure** platform for commercial entitlements, usage metering, strict quota enforcement, contract-version history, commercial period finalization, reconciliation, and failure-safe event processing.

## Engineering highlights

- Three independently deployable Java 21 / Spring Boot 3 workloads on shared PostgreSQL.
- Transactional outbox + consumer inbox: at-least-once Kafka transport with idempotent business effects.
- PostgreSQL-authoritative quota admission under concurrency (not JVM locks, not reporting aggregates).
- Commercial finalization that can still accept delayed ledger evidence without silently rewriting aggregates.
- Reconciliation as rebuild/compare/report; corrections only via explicit `usage_adjustment`.
- Failure drills (Testcontainers + kind), a measured local performance lab, Terraform AWS topology, GitHub Actions delivery design.

## Evidence (verified)

- Automated Maven suite (count is whatever `.\mvnw.cmd clean verify` prints; do not freeze a vanity number here).
- 23 ADRs; three runtime workloads; no fourth “performance service.”
- Phase 10 named failure windows; Phase 11 recorded local Gatling; Phase 12 live kind smoke + drills.
- Phase 13 Terraform fmt/validate; Phase 14 workflow configuration.

## Technology (actually used)

Java 21 · Spring Boot 3 · Maven · PostgreSQL · Flyway · Kafka (JSON) · Testcontainers · JUnit 5 · REST Assured · Mockito · ArchUnit · Micrometer · OpenTelemetry · Prometheus · Grafana · Gatling · JFR · Docker · Kubernetes (kind) · Helm · Terraform · AWS architecture-as-code · GitHub Actions

Intentionally **not** used: Redis, Kafka Streams, MongoDB, Elasticsearch, GraphQL, service mesh, AI/LLM, frontend.

## Limitations

See [limitations.md](../limitations.md). Headline: RLS deferred; no live AWS apply by default; CI/CD cloud execution not assumed; local performance ≠ production capacity.
