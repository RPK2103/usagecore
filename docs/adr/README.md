# Architecture Decision Records

All ADRs below are **Accepted**. ADR-006 is accepted as an intentional deferral, not as “RLS is implemented.”

Do not treat formatting differences across older ADRs as defects. Historical context is preserved.

| ADR | Title | One-line decision |
| --- | --- | --- |
| [001](ADR-001-application-boundaries.md) | Application boundaries | Pragmatic hexagonal architecture; domain has no Spring MVC / Kafka / PostgreSQL / AWS / HTTP dependencies. |
| [002](ADR-002-postgresql-tenancy.md) | PostgreSQL tenancy | Shared schema with mandatory application `tenant_id` scoping. |
| [003](ADR-003-contract-historical-state.md) | Contract historical state | Activated `ContractVersion` (and entitlement snapshots) is immutable historical commercial evidence. |
| [004](ADR-004-plan-vs-contract.md) | Plan vs contract | Plans are templates; activated contracts do not change when plans change. |
| [005](ADR-005-temporal-model.md) | Temporal model | Half-open UTC intervals `[effectiveFrom, effectiveUntil)`. |
| [006](ADR-006-postgresql-rls.md) | PostgreSQL RLS | **Deferred for v1** — pooled connections make session-variable RLS easy to leak; application JWT tenancy remains the control. |
| [007](ADR-007-entitlement-runtime-read-architecture.md) | Entitlement Runtime | Independent read workload against shared PostgreSQL snapshots; no Control Plane compile-time dependency. |
| [008](ADR-008-kafka-usage-topology.md) | Kafka usage topology | JSON `UsageReceived` envelopes, at-least-once, partition key `tenantId\|productKey\|meterKey`. |
| [009](ADR-009-transactional-outbox-ingestion-idempotency.md) | Transactional outbox | HTTP 202 after PostgreSQL commit of `usage_ingestion` + PENDING `outbox_event`; Kafka publish is asynchronous. |
| [010](ADR-010-consumer-inbox-and-idempotent-processing.md) | Consumer inbox | `processed_event` uniqueness makes duplicate Kafka delivery a successful no-op. |
| [011](ADR-011-metering-and-aggregation.md) | Metering and aggregation | Deterministic PostgreSQL UPSERT aggregates; Kafka Streams deferred. |
| [012](ADR-012-event-time-and-windowed-metering.md) | Event-time windows | Window ownership from `occurredAt`; late events accepted; Kafka Streams still deferred. |
| [013](ADR-013-contract-aware-quota-enforcement.md) | Contract-aware quota | PostgreSQL `quota_state` is concurrency authority; reporting aggregates are not. |
| [014](ADR-014-commercial-period-lifecycle.md) | Commercial period lifecycle | Explicit `OPEN → CLOSING → RECONCILING → FINALIZED`; delayed events may ledger + quarantine without mutating finalized aggregates. |
| [015](ADR-015-reconciliation-and-deterministic-rebuild.md) | Reconciliation | Read / rebuild / compare / report from `usage_ledger`. No silent repair. |
| [016](ADR-016-explicit-usage-adjustments.md) | Usage adjustments | Only `APPLY_QUARANTINED_USAGE`; immutable `usage_adjustment`; `quota_state` unchanged. |
| [017](ADR-017-observability-architecture.md) | Observability | Metrics, structured logs, W3C traces describe behavior; they are not commercial authority. |
| [018](ADR-018-operational-dashboards-and-alerting.md) | Dashboards and alerting | Local Grafana + Prometheus demo alerts; no automatic remediation. |
| [019](ADR-019-resilience-and-failure-recovery.md) | Resilience | Selected Kafka/PostgreSQL/outbox/inbox/finalization windows proven; not HA or disaster recovery. |
| [020](ADR-020-performance-engineering-and-benchmark-methodology.md) | Performance methodology | Measure first; local Gatling is not production capacity. |
| [021](ADR-021-kubernetes-packaging-and-operability.md) | Kubernetes | kind + Helm operability for the three workloads; Kubernetes is not data durability. |
| [022](ADR-022-aws-deployment-architecture-and-terraform.md) | AWS / Terraform | Target EKS/RDS/MSK topology expressed as Terraform; configuration validated, not live-applied by default. |
| [023](ADR-023-github-actions-ci-cd-and-supply-chain-security.md) | CI/CD | GitHub Actions with SHA-pinned actions, OIDC, plan/apply separation; live GitHub/AWS execution is a separate evidence class. |

There is no ADR-024. Portfolio hardening did not create a new architectural decision.

Numbering is sequential 001–023 with no gaps or duplicates.
