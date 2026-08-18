# UsageCore documentation

Start with the repository [README](../README.md) (what the system is, how to run it, what was proven).

This index is the rest of the map. Prefer these entry points over searching the tree.

## Architecture

| Document | Purpose |
| --- | --- |
| [System overview](architecture/system-overview.md) | Three workloads, boundaries, tenancy, usage/commercial model |
| [Diagrams](architecture/diagrams.md) | Runtime, outbox/inbox, quota, commercial lifecycle, deployment topology |
| [Source of truth](architecture/source-of-truth.md) | Canonical vs derived vs operational vs historical tables |
| [Domain model](architecture/domain-model.md) | Commercial entities and lifecycle rules |
| [Event catalogue](architecture/events.md) | Kafka `UsageReceived` envelope and semantics |
| [Deployment matrix](architecture/deployment-matrix.md) | Compose / Testcontainers / lab / kind / AWS / GitHub |
| [Initial ER model](architecture/initial-er-model.md) | Historical Phase 1 logical model (not the current physical schema) |

## ADRs

[ADR index](adr/README.md) — 23 accepted decisions, including deferred PostgreSQL RLS ([ADR-006](adr/ADR-006-postgresql-rls.md)).

## Security

| Document | Purpose |
| --- | --- |
| [Security model](security/README.md) | JWT, tenant authority, RBAC, secrets, remaining limitations |
| [AWS security](aws/security.md) | Target IAM / network / Secrets Manager design |
| [CI/CD security](cicd/security.md) | Workflow permissions, OIDC, scanners |

## Observability

| Document | Purpose |
| --- | --- |
| [Local setup](observability/local-observability.md) | Prometheus, Grafana, OTel Collector |
| [Metrics](observability/metrics.md) | Bounded-cardinality catalogue |
| [Alerts](observability/alerts.md) | Demo thresholds (not production SLOs) |
| [Runbooks](observability/runbooks/) | Investigation paths; no automatic remediation |

## Resilience

| Document | Purpose |
| --- | --- |
| [Failure matrix](resilience/failure-matrix.md) | Phase 10 Testcontainers proofs + deferred items |
| [Kubernetes failure matrix](kubernetes/failure-matrix.md) | Live kind drills, including pending-outbox pod restart |

## Performance

| Document | Purpose |
| --- | --- |
| [Lab overview](performance/README.md) | How to measure; not a service |
| [Portfolio summary](performance/summary.md) | What was measured, where saturation appeared |
| [Baseline results](performance/baseline-results.md) | Recorded local numbers with environment context |
| [Methodology](performance/methodology.md) | Profiles, auth, coordinated omission |
| [Database analysis](performance/database-analysis.md) | EXPLAIN ANALYZE |
| [Profiling](performance/profiling-guide.md) | JFR |

## Kubernetes

| Document | Purpose |
| --- | --- |
| [Kubernetes README](kubernetes/README.md) | kind + Helm operability |
| [Local cluster](kubernetes/local-cluster.md) | Cluster topology |
| [Deployment](kubernetes/deployment.md) | Chart, probes, replicas |
| [Operability validation](kubernetes/operability-validation.md) | What was live-run |

## AWS

| Document | Purpose |
| --- | --- |
| [AWS README](aws/README.md) | Target architecture; **not** a live deployment |
| [Architecture](aws/architecture.md) | EKS / RDS / MSK mapping |
| [Deployment model](aws/deployment-model.md) | Terraform vs Helm |
| [Cost and limitations](aws/cost-and-limitations.md) | NAT, MSK, EKS, RDS, destroyability |
| [Terraform validation](aws/terraform-validation.md) | What fmt/validate prove |

## CI/CD

| Document | Purpose |
| --- | --- |
| [CI/CD README](cicd/README.md) | Workflows; configuration unless GitHub actually ran them |
| [Pipeline](cicd/pipeline.md) | PR → image → gated deploy |
| [Deployment](cicd/deployment.md) | Helm, secrets, MSK, smoke |
| [Rollback](cicd/rollback.md) | Helm vs schema vs Terraform |
| [Evidence](cicd/evidence.md) | Executed vs configured |

## Demo

| Document | Purpose |
| --- | --- |
| [Walkthrough](demo/README.md) | 10–15 minute evaluator path |
| [API examples](demo/api-examples.md) | Flagship request/response semantics |

## Evidence and limitations

| Document | Purpose |
| --- | --- |
| [Engineering evidence](evidence/engineering-evidence.md) | Claim → evidence type → where to inspect → limitation |
| [Test strategy](evidence/test-strategy.md) | What each test category proves |
| [Quality matrix](evidence/quality-matrix.md) | Area / implementation / authority / evidence / limitation |
| [Limitations](limitations.md) | Deliberately unproven and deferred work |

## Portfolio / interview

| Document | Purpose |
| --- | --- |
| [Reviewer guide](portfolio/reviewer-guide.md) | Five-minute hiring-manager tour |
| [Interview guide](portfolio/interview-guide.md) | Inspectable flagship stories |
| [Project summary](portfolio/project-summary.md) | One-page technical summary |

## Roadmap

[Roadmap](roadmap.md) — completed phases 0–15 and deferred Phase 7B (PostgreSQL RLS).
