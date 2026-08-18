# System quality matrix

| Area | Implementation | Primary authority | Evidence | Known limitation |
| --- | --- | --- | --- | --- |
| Multi-tenancy | Shared PostgreSQL schema; JWT `tenant_id`; repository scoping | Authenticated principal | Security/API tests | PostgreSQL RLS deferred (Phase 7B) |
| Contract versioning | DRAFT → ACTIVATED; frozen entitlements | Activated `ContractVersion` | Domain + schema tests | Plan edits never rewrite activated history |
| Entitlement decisions | `POST /entitlements/check` | Snapshot entitlements | API tests | Read-only; no remaining-quota field |
| Usage ingestion | `POST /usage/events` → PG ingest + outbox | `usage_ingestion` + `outbox_event` | Ingestion/idempotency tests | 202 ≠ consumer complete |
| Outbox | PENDING → Kafka → PUBLISHED; `SKIP LOCKED` | PostgreSQL outbox row | Publisher + Kafka-outage tests; kind pending-outbox drill | ACK-before-PUBLISHED can duplicate transport |
| Inbox | `processed_event` unique on `eventId` | Inbox uniqueness | Duplicate-storm / crash-window tests | At-least-once transport remains |
| Aggregation | SUM/COUNT/MAX UPSERT; event-time windows | Derived aggregates; ledger is canonical | Aggregation + late-event tests | Kafka Streams not used |
| Quota | `POST /usage/consume`; conditional UPDATE | `quota_state` | Concurrency tests | Multi-replica live consume race on kind not executed |
| Commercial lifecycle | OPEN→CLOSING→RECONCILING→FINALIZED | `commercial_period` | Period API + processing tests | No tenant-specific timezones / auto-scheduler |
| Reconciliation | READ/REBUILD/COMPARE/REPORT | Canonical ledger vs derived | Reconciliation API tests | Does not auto-repair; stale RUNNING unreaped |
| Adjustments | `APPLY_QUARANTINED_USAGE` only | `usage_adjustment` + aggregate update | Adjustment API tests | No compensating undo; quota unchanged |
| Observability | Micrometer, OTel, Grafana, Prometheus | Not commercial | Metric/dashboard tests | Demo alert thresholds, no paging |
| Resilience | Testcontainers pause + seams | Same SoT tables | Phase 10 suite | Not HA/DR; DLQ dest outage deferred |
| Performance | Local Gatling lab | Measurement only | Phase 11 recorded runs | Laptop + Docker; not cloud capacity |
| Kubernetes | kind + Helm, non-root images | Operability, not durability | Phase 12 smoke + drills | Not EKS; NetworkPolicy unproven |
| AWS | Terraform EKS/RDS/MSK/ECR | Target architecture | fmt/validate | Not live-applied |
| CI/CD | GitHub Actions, OIDC, SHA images | Delivery design | Workflow files; GitHub Terraform/Container succeeded on `24975ee`; Java verify and Trivy IaC failed (causes addressed in Phase 15, not re-run on GitHub) | OIDC/ECR/apply unexecuted; environment protection unverified |
