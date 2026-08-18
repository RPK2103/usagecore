# Interview guide

Not marketing copy. Each story is inspectable in this repository. Prefer pointing at code, tests, and ADRs over memorized speeches.

## 1. Why UsageCore exists

**Problem.** B2B SaaS products need commercial entitlements, usage metering, and quota admission that stay correct when events are delayed, duplicated, or retried — without pretending to be a billing system.

**Decision.** Build a multi-tenant platform with explicit correctness authorities: activated contracts, a usage ledger, PostgreSQL quota state, and commercial periods.

**Implementation.** Three Spring Boot 3 / Java 21 workloads sharing PostgreSQL via Flyway; Kafka only as usage transport.

**Failure scenario.** Treating HTTP 202 or Kafka offsets as “usage billed” would lie about commercial state.

**Trade-off.** No invoices, credits, or pricing engine. Those would be a different product.

**Evidence.** README, [source of truth](../architecture/source-of-truth.md), ADRs 001–016.

**Unproven.** Real customer billing integrations.

## 2. Why three deployables

**Problem.** Catalogue mutation, low-latency entitlement reads, and usage processing have different failure and scale characteristics.

**Decision.** Control Plane, Entitlement Runtime, Usage Pipeline as independently deployable apps on one schema.

**Implementation.** Separate Maven modules; Control Plane owns Flyway; the others default `flyway.enabled=false`.

**Failure scenario.** A usage-pipeline Kafka backlog must not take down entitlement checks if PostgreSQL is healthy.

**Trade-off.** Operationally three processes; no extra databases or Kafka Streams apps.

**Evidence.** [ADR-001](../adr/ADR-001-application-boundaries.md), [ADR-007](../adr/ADR-007-entitlement-runtime-read-architecture.md), Helm replica settings (CP 1, ER 2, UP 2).

**Unproven.** Independent schema split or read replicas.

## 3. Why PostgreSQL is source of truth

**Problem.** Kafka, caches, and dashboards are poor commercial ledgers.

**Decision.** PostgreSQL holds canonical usage, quota, contracts, and periods. Kafka carries envelopes.

**Implementation.** Flyway V1–V13; transactional ingest+outbox; consumer inbox+ledger in one DB transaction.

**Failure scenario.** Dual-write HTTP→DB and HTTP→Kafka without a transaction loses or double-applies facts.

**Trade-off.** Hot partitions and row-level quota contention hit PostgreSQL first. That is accepted until measured otherwise.

**Evidence.** [source of truth](../architecture/source-of-truth.md), Phase 10 Kafka-down still 202.

**Unproven.** Multi-AZ RDS failover.

## 4. Transactional outbox

**Problem.** Direct DB + Kafka dual writes are not atomic.

**Decision.** Same PostgreSQL transaction writes `usage_ingestion` and PENDING `outbox_event`; HTTP 202 after COMMIT; async publisher sends to Kafka then marks PUBLISHED.

**Implementation.** `FOR UPDATE SKIP LOCKED` publisher; JSON envelope stored exactly.

**Failure scenario.** Kafka ACK then crash before PUBLISHED → publisher retries → duplicate **transport**.

**Trade-off.** At-least-once publication. Inbox must exist (story 5).

**Evidence.** [ADR-009](../adr/ADR-009-transactional-outbox-ingestion-idempotency.md), `OutboxCrashWindowIntegrationTest`, kind pending-outbox drill.

**Unproven.** Multi-broker producer idempotence across MSK failovers.

Phrase: **at-least-once transport with idempotent business effects.** Not “exactly-once system.”

## 5. Consumer inbox

**Problem.** After a DB commit, offset progression can fail; Kafka redelivers the same `eventId`.

**Decision.** `processed_event` uniqueness is duplicate authority. Second delivery is a successful no-op.

**Implementation.** One transaction: inbox + `usage_ledger` + aggregates **or** commercial exception.

**Failure scenario.** Duplicate storm of 100 replays → still one ledger row.

**Trade-off.** Consumer must not apply side effects outside that transaction.

**Evidence.** [ADR-010](../adr/ADR-010-consumer-inbox-and-idempotent-processing.md), duplicate-storm and crash-window tests.

**Unproven.** Poison handling when the DLQ topic itself is down.

## 6. Strict quota concurrency

**Problem.** `remaining = limit - aggregate` races. Reporting aggregates lag. JVM locks fail across replicas.

**Decision.** `POST /usage/consume` with PostgreSQL conditional UPDATE on `quota_state`.

**Implementation.** LIMITED + SUM/COUNT; MAX unsupported for quota; consume also writes outbox on accept.

**Failure scenario.** Limit 100, consumed 90, 20 concurrent × 1 → exactly 10 accepted, final 100.

**Trade-off.** Row contention serializes hot meters. Local closed-model p95 rose vs open 5 rps; no cache was added.

**Evidence.** `QuotaConsumptionConcurrencyIntegrationTest`, [ADR-013](../adr/ADR-013-contract-aware-quota-enforcement.md), Phase 11 contention profile.

**Unproven.** Live two-replica consume race on kind (documented optional drill).

## 7. Commercial-period finalization

**Problem.** An event can be durably accepted, delayed in Kafka, and arrive after the period is FINALIZED.

**Decision.** Preserve ledger evidence; write `commercial_usage_exception`; do not silently rewrite finalized aggregates.

**Implementation.** Period lifecycle on Control Plane; Usage Pipeline enforces on consume/process; `FOR SHARE` vs usage mutation.

**Failure scenario.** Delayed delivery test: ledger 1, exception 1, aggregates unchanged.

**Trade-off.** Reporting totals can diverge from canonical ledger until an explicit adjustment. That is visible, not hidden.

**Evidence.** [ADR-014](../adr/ADR-014-commercial-period-lifecycle.md), `DelayedDeliveryFinalizationIntegrationTest`.

**Unproven.** Automated period close on a clock/timezone per tenant.

## 8. Reconciliation

**Problem.** Finalization does not prove derived totals match the ledger.

**Decision.** READ → REBUILD → COMPARE → REPORT. Not REPAIR.

**Implementation.** Rebuild expected window/lifetime totals from `usage_ledger` + meter semantics; immutable `reconciliation_run` / items.

**Failure scenario.** MATCH does not auto-finalize. FINALIZE blocked while a run is RUNNING.

**Trade-off.** Operators must apply `usage_adjustment` for quarantined events. No silent historical rewrite.

**Evidence.** [ADR-015](../adr/ADR-015-reconciliation-and-deterministic-rebuild.md), [ADR-016](../adr/ADR-016-explicit-usage-adjustments.md).

**Unproven.** Reaper for crashed RUNNING runs.

## 9. Resilience testing

**Problem.** Happy-path tests never pause Kafka or PostgreSQL.

**Decision.** Deterministic Testcontainers pause/unpause plus two explicit crash seams. No Resilience4j/Toxiproxy tax.

**Implementation.** Proven: Kafka outage + backlog drain, ACK-before-PUBLISHED, consumer pre-commit fail, DB-commit/offset gap, PG unavailability, poison isolation, delayed finalization. Kind added pending-outbox pod restart.

**Failure scenario.** Distinguish Testcontainers seams from actual pod deletion.

**Trade-off.** Not chaos-of-everything; selected windows with named tests.

**Evidence.** [failure matrix](../resilience/failure-matrix.md), [k8s matrix](../kubernetes/failure-matrix.md).

**Unproven.** DLQ destination outage, destructive DB DR, AWS failover.

## 10. Performance engineering

**Problem.** Invented TPS numbers are worse than no numbers.

**Decision.** Measure first; change production code only with before/after evidence.

**Implementation.** Separate workloads A/B/C; warm-up excluded; cached Keycloak token; JFR + EXPLAIN.

**Failure scenario.** Burst HTTP 202 drained outbox quickly locally while consumer lagged — PENDING=0 is not ledger completion.

**Trade-off.** No production index/Hikari change: saturation was not a 5xx/GC/Hikari-timeout knee at documented local rates.

**Evidence.** [performance/summary.md](../performance/summary.md). Environment: Windows 11 laptop, Ryzen 7 7735HS, ~15 GiB RAM, 2026-08-18.

**Unproven.** Cloud capacity; higher ingest than ~30 rps local burst.

## 11. Kubernetes operability

**Problem.** JARs on a laptop do not prove probes, replicas, or pod replacement.

**Decision.** kind + Helm for the same three workloads. Java apps are Deployments. DB/Kafka are durability dependencies (in-cluster for local proof only).

**Implementation.** Non-root images; readiness PostgreSQL-only on Usage Pipeline; shared consumer group; SKIP LOCKED outbox; inbox uniqueness.

**Failure scenario.** Kafka down → 202 + PENDING → delete usage-pipeline pod → restore Kafka → one business effect.

**Trade-off.** Kubernetes does not make data durable. PVC/in-cluster Kafka is a local stand-in, not RDS/MSK.

**Evidence.** Phase 12 scripts and [kubernetes/failure-matrix.md](../kubernetes/failure-matrix.md).

**Unproven.** EKS, NetworkPolicy, worker-node failure.

## 12. AWS mapping

**Problem.** Evaluators will assume Terraform means “we deployed AWS.”

**Decision.** Express EKS + RDS PostgreSQL + MSK + ECR + ALB + Secrets Manager + OIDC/IAM as code. Do not apply for a résumé screenshot.

**Implementation.** `infrastructure/terraform`; Helm `values-aws.yaml`; cost-aware defaults (one NAT, single-AZ RDS, two MSK brokers).

**Failure scenario.** Accidental `terraform apply` incurs NAT/MSK/EKS/RDS cost.

**Trade-off.** Configuration validation is weaker than live runtime proof and **honest**. Live apply requires explicit authorization.

**Evidence.** [ADR-022](../adr/ADR-022-aws-deployment-architecture-and-terraform.md), `terraform validate`.

**Unproven.** Everything that needs an account and a bill.

## 13. CI/CD security

**Problem.** Long-lived AWS keys in GitHub, mutable `latest` tags, and plan==apply are common failure modes.

**Decision.** PR verify; SHA-pinned actions; immutable git-SHA images; OIDC short-lived roles; Terraform plan separated from apply; environment `dev` gate; Flyway remains app-owned.

**Implementation.** Workflows under `.github/workflows`; Dependabot; CodeQL/Trivy config; no auto-merge.

**Failure scenario.** A green local `helm lint` does not prove GitHub ran, OIDC worked, or Helm hit EKS.

**Trade-off.** Delivery is designed; cloud pipeline execution is a separate evidence class.

**Evidence.** [ADR-023](../adr/ADR-023-github-actions-ci-cd-and-supply-chain-security.md), [cicd/evidence.md](../cicd/evidence.md).

**Unproven.** GitHub-hosted runs and live AWS mutation unless recorded.
