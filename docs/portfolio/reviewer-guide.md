# Reviewer guide (about five minutes)

If you have five minutes, read in this order. UsageCore is a backend/infrastructure project; there is no product UI.

## 1. What it is (1 min)

[README](../../README.md) — multi-tenant commercial entitlements, usage metering, strict quota, contract-version history, commercial periods, reconciliation, failure-safe event processing.

Not a billing platform. Not exactly-once. Not a live AWS deployment.

## 2. Architecture (1 min)

[System overview](../architecture/system-overview.md) and [diagrams](../architecture/diagrams.md).

Three deployables: Control Plane, Entitlement Runtime, Usage Pipeline. PostgreSQL is source of truth. Kafka is transport.

## 3. Correctness (1 min)

[Source of truth](../architecture/source-of-truth.md) — ledger vs aggregates vs quota vs outbox/inbox.

Then one test name: `QuotaConsumptionConcurrencyIntegrationTest` (limit 100 / 90 consumed / 20 concurrent → 10 accepted).

## 4. Failure and measurement (1 min)

- Resilience: [failure matrix](../resilience/failure-matrix.md)
- Performance: [summary](../performance/summary.md) (local laptop, not cloud TPS)
- Kubernetes: [kind failure matrix](../kubernetes/failure-matrix.md) (pending-outbox pod restart)

## 5. Cloud and delivery (1 min)

- AWS: [aws/README.md](../aws/README.md) — **target architecture**, Terraform validated, not applied
- CI/CD: [cicd/evidence.md](../cicd/evidence.md) — workflows exist; GitHub/AWS execution not assumed
- Limitations: [limitations.md](../limitations.md) including deferred **Phase 7B RLS**

## Optional next

| If you care about… | Open |
| --- | --- |
| Demo commands | [demo/README.md](../demo/README.md) |
| Interview depth | [interview-guide.md](interview-guide.md) |
| Claim ↔ evidence | [engineering-evidence.md](../evidence/engineering-evidence.md) |
| Quality snapshot | [quality-matrix.md](../evidence/quality-matrix.md) |
| ADRs | [adr/README.md](../adr/README.md) |

Full map: [docs/README.md](../README.md).
