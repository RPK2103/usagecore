# Engineering evidence index

Labels are not interchangeable. Do not upgrade a claim without new execution.

## Taxonomy

| Label | Meaning |
| --- | --- |
| VERIFIED BY TEST | Automated test in `clean verify` |
| VERIFIED BY CODE INSPECTION | Behavior follows from code/config; no dedicated live drill |
| VERIFIED BY DATABASE CONSTRAINT | Uniqueness, check, exclusion, or foreign key in Flyway |
| VERIFIED BY LIVE PERFORMANCE RUN | Recorded Gatling/JFR/EXPLAIN on a documented local machine |
| VERIFIED BY JFR | Flight recorder samples from that lab |
| VERIFIED BY EXPLAIN ANALYZE | PostgreSQL plans from that lab |
| VERIFIED BY CONTAINER BUILD | Image or Compose file builds/validates |
| VERIFIED BY LIVE KUBERNETES SMOKE | Authenticated kind smoke actually run |
| VERIFIED BY LIVE KUBERNETES FAILURE DRILL | kind drill actually run |
| VERIFIED BY TERRAFORM VALIDATE | `terraform fmt` / `validate` (no AWS required) |
| VERIFIED BY HELM LINT | `helm lint` / `helm template` |
| VERIFIED BY WORKFLOW LINT | Workflow YAML statically checked (actionlint or equivalent if run) |
| CONFIGURATION VALIDATED ONLY | Files exist and parse; runtime not executed |
| REASONED BUT NOT EXECUTED | Design argues it; no dedicated experiment |
| DEFERRED | Intentionally not done |

## Claims

| Claim | Evidence | Type | Where to inspect | Limitation |
| --- | --- | --- | --- | --- |
| Three independently deployable workloads | Modules + Dockerfiles + Helm | VERIFIED BY CODE INSPECTION / CONTAINER BUILD | `applications/*`, `infrastructure/kubernetes` | Not a service mesh or additional runtime |
| PostgreSQL is commercial source of truth | Domain + Flyway + tests | VERIFIED BY TEST + DATABASE CONSTRAINT | `libraries/database-migrations`, ADRs 002–016 | RLS deferred |
| Activated contract versions are immutable | Domain + schema + tests | VERIFIED BY TEST | `ContractCatalogueInvariantTest`, ADR-003 | Corrections via new version, not in-place rewrite |
| Tenant authority is JWT-only | Security tests | VERIFIED BY TEST | `ControlPlaneSecurityIntegrationTest`, entitlement/usage API tests | Application filtering is not PostgreSQL RLS |
| HTTP 202 is durable PG acceptance | Ingestion + outbox tests; Kafka pause still 202 | VERIFIED BY TEST | `UsageIngestionApiIntegrationTest`, `KafkaBrokerOutageIntegrationTest` | Not ledger completion |
| Consumer duplicates do not double-apply | Inbox uniqueness + duplicate-storm test | VERIFIED BY TEST + DATABASE CONSTRAINT | `processed_event`, `Poison`/`ConsumerCrash`/`IdempotentConsumer*` tests | Transport remains at-least-once |
| Outbox ACK-before-PUBLISHED may duplicate publish | Crash-window test | VERIFIED BY TEST | `OutboxCrashWindowIntegrationTest` | Test-only publisher gate; not a JVM kill |
| Consumer DB-commit then offset gap is safe | Fail-once after process | VERIFIED BY TEST | `ConsumerCrashWindowIntegrationTest` | Test seam, not process kill |
| Strict quota under concurrency | limit 100, consumed 90, 20×1 → 10 accepted | VERIFIED BY TEST | `QuotaConsumptionConcurrencyIntegrationTest` | Multi-replica live quota race on kind is REASONED BUT NOT EXECUTED |
| Delayed delivery after FINALIZED quarantines | Ledger + exception; aggregates unchanged | VERIFIED BY TEST | `DelayedDeliveryFinalizationIntegrationTest` | Uses current period state, not accept-time |
| Reconciliation reports; it does not repair | API + domain tests | VERIFIED BY TEST | `ReconciliationApiIntegrationTest`, ADR-015 | Stale `RUNNING` recovery DEFERRED |
| Adjustments are explicit and immutable | API + constraint | VERIFIED BY TEST + DATABASE CONSTRAINT | `UsageAdjustmentApiIntegrationTest`, V13 | Only `APPLY_QUARANTINED_USAGE` |
| Kafka outage does not fail ingest HTTP | 202 + readiness UP | VERIFIED BY TEST + LIVE KUBERNETES FAILURE DRILL | Phase 10 test + Phase 12 kafka-outage drill | Single broker |
| Pending outbox survives pod replacement | kind drill | VERIFIED BY LIVE KUBERNETES FAILURE DRILL | [kubernetes/failure-matrix.md](../kubernetes/failure-matrix.md) | Local kind, not EKS |
| Performance baseline | Gatling on documented laptop | VERIFIED BY LIVE PERFORMANCE RUN | [performance/baseline-results.md](../performance/baseline-results.md) | Not production capacity |
| Bottleneck analysis | EXPLAIN + JFR | VERIFIED BY EXPLAIN ANALYZE / JFR | [database-analysis.md](../performance/database-analysis.md), profiling guide | Single local run |
| No production optimization was justified | No app/index change after lab | VERIFIED BY CODE INSPECTION vs Phase 11 docs | ADR-020, baseline-results Optimizations | Higher cloud load not measured |
| AWS topology | Terraform | VERIFIED BY TERRAFORM VALIDATE | `infrastructure/terraform` | Not live-applied |
| Helm local vs AWS overlay | lint + template both | VERIFIED BY HELM LINT | `values.yaml` vs `values-aws.yaml` | AWS overlay not deployed |
| CI/CD pipelines | Workflow YAML | CONFIGURATION VALIDATED ONLY | `.github/workflows` | GitHub-hosted execution not assumed |
| GitHub-hosted Terraform/Container on `24975ee` | Actions runs | VERIFIED BY GITHUB ACTIONS | [cicd/evidence.md](../cicd/evidence.md) | Plan/apply, ECR, OIDC, deploy not executed |
| GitHub-hosted Maven verify / Trivy IaC on `24975ee` | Actions runs | VERIFIED BY GITHUB ACTIONS (failed) | same | Failures recorded; Phase 15 applies bounded workflow/IaC fixes, not yet re-proven on GitHub |
| GitHub OIDC / ECR / EKS deploy | Workflow + Terraform IAM | REASONED BUT NOT EXECUTED (unless recorded) | [cicd/evidence.md](../cicd/evidence.md) | Cost-bearing AWS not auto-run |
| PostgreSQL RLS | ADR-006 | DEFERRED | Phase 7B on roadmap | Do not call app filtering “database tenant isolation” |
| DLQ destination outage | Recoverer inspection | VERIFIED BY CODE INSPECTION; experiment DEFERRED | ADR-019 | No second DLQ |
| Destructive DB disaster recovery | — | DEFERRED | — | Volume wipe not tested |
| Autoscaling | — | DEFERRED | Helm replicas are static | HPA not proven |

Phase 11 remains the performance evidence. Phase 12 remains the live Kubernetes evidence. Phase 13 remains configuration-only for AWS runtime. Phase 14 GitHub-hosted execution on `24975ee` is recorded in [cicd/evidence.md](../cicd/evidence.md) (Terraform/Container succeeded; Java verify and Trivy IaC failed). OIDC/ECR/apply remain unexecuted.
