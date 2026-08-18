# Test strategy

Automated tests run with:

```powershell
.\mvnw.cmd clean verify
```

Docker is required for Testcontainers (PostgreSQL and Kafka). Gatling profiles are **not** part of `clean verify`.

Do not treat this file as a frozen test count. Report the number printed by Maven after each full run.

## Categories

| Category | What it covers | What it proves |
| --- | --- | --- |
| Domain / unit | Catalogue invariants, entitlement decision logic, event envelope serialization, metric helpers | Invariants without I/O: immutability, plan vs contract, period transitions as domain rules |
| REST / API | REST Assured against Spring MVC with Testcontainers DB (and Kafka where needed) | Authn/z, JSON contracts, HTTP 202 vs 200 semantics, idempotency 409, commercial REJECTED as 200 |
| Security | JWT resource server, role matrix, cross-tenant 403, unknown `tenantId` in body rejected | Tenant authority is the authenticated principal; body/header tenant selectors are not authorization |
| PostgreSQL Testcontainers | Flyway migrations, constraints, UPSERT, SKIP LOCKED, quota UPDATE | Schema and transactional correctness on real PostgreSQL 16, not H2 |
| Kafka Testcontainers | Outbox publish, consumer inbox, DLQ, aggregation | At-least-once delivery plus idempotent apply on a real broker |
| Concurrency | `QuotaConsumptionConcurrencyIntegrationTest` and related | PostgreSQL is admission authority under races; JVM locks are not relied on |
| Resilience / failure injection | Docker pause Kafka/PostgreSQL; publisher/consumer crash seams | Selected failure windows: 202 during Kafka outage, ACK-before-PUBLISHED, commit/offset gap, poison isolation, delayed finalization |
| Observability | Metric names/labels, Grafana JSON provisioning, alert rule files | Bounded cardinality and dashboard/alert files stay aligned with code |
| Architecture (ArchUnit) | Domain must not depend on Spring MVC / Kafka / JDBC / AWS | Hexagonal boundary is mechanically enforced |
| AWS Kafka config | SASL_SSL/SCRAM profile vs local PLAINTEXT | Cloud Kafka settings exist and conflict-detect locally; **not** a live MSK test |
| Performance harness smoke | `PerformanceHarnessSmokeTest` | Lab module wiring. **Not** a load result |

## What the suite does not prove

- GitHub-hosted workflow execution
- Live AWS (OIDC, ECR, RDS, MSK, EKS)
- Production SLOs
- Multi-AZ failover
- NetworkPolicy enforcement
- Disaster recovery from volume destruction

Those belong to other evidence classes in [engineering-evidence.md](engineering-evidence.md).
