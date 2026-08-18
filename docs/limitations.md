# Limitations

Deliberate gaps. Do not read silence as “solved.”

## Domain / data

- PostgreSQL Row Level Security is **deferred** (Phase 7B, [ADR-006](adr/ADR-006-postgresql-rls.md)).
- No pricing, invoices, credits, or billing exports. UsageCore is not a billing platform.
- No compensating/undo UsageAdjustment; only `APPLY_QUARANTINED_USAGE`.
- No automatic exception application, quota repair, or Kafka historical replay.
- No automated commercial-period scheduler or tenant-specific timezones.
- Stale reconciliation `RUNNING` after a crash is not reaped (manual operational handling).

## Distributed systems

- Delivery is **at-least-once**. End-to-end exactly-once is not claimed.
- DLQ **destination** outage is not experimentally proven.
- Kafka Streams, Schema Registry, and Avro are intentionally unused.

## Resilience / operations

- Destructive PostgreSQL disaster recovery (volume wipe / backup restore) is **deferred**.
- Phase 10 used Testcontainers pause and test seams; process kill under load is not fully covered there.
- Phase 12 closed pending-outbox **pod** restart on kind; it is not AWS failover.
- No PagerDuty / production notification routing / automatic remediation.
- Alert thresholds are demo/engineering defaults, not production SLOs.
- Autoscaling (HPA/KEDA) is not proven; Helm replica counts are static.

## Performance

- Gatling numbers are one documented developer laptop + Docker Desktop.
- They are not cloud, production, or hardware-independent capacity.

## Kubernetes

- Live evidence is **kind**, not EKS.
- NetworkPolicy not proven.
- Optional drills (consumer kill under load, quota across two replicas with live traffic, worker-node failure) remain unexecuted as documented.

## AWS

- Target architecture only unless `terraform apply` is **explicitly authorized and executed**.
- Not proven live: EKS deploy, RDS failover, MSK broker failure, AZ/region failure, OIDC, ECR push, Helm on EKS, Secrets Manager sync, AWS smoke.
- Dev trade-offs remain: single NAT, single-AZ RDS, non-production instance sizes. Those are cost choices, not HA.

## CI/CD

- Workflows are implemented in `.github/workflows`.
- First GitHub-hosted run on Phase 14 `24975ee`: Terraform + Container succeeded; CI Java/Verify failed (`mvnw` permissions); Trivy IaC failed on documented example findings. Phase 15 records this and applies bounded fixes; a later hosted run is required before claiming those jobs green.
- GitHub environment protection / required checks are **not assumed**.
- See [cicd/evidence.md](cicd/evidence.md).

## Product / repo

- No frontend, Redis, MongoDB, Elasticsearch, GraphQL, or service mesh.
- No AI/LLM components.
- No license file has been added (none was chosen).
