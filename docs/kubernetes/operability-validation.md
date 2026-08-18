# Operability validation (Phase 12)

Evidence labels used in this milestone:

- **VERIFIED BY LIVE KUBERNETES SMOKE**
- **VERIFIED BY LIVE KUBERNETES FAILURE DRILL**
- **VERIFIED BY DATABASE STATE**
- **VERIFIED BY KUBERNETES CONFIGURATION**
- **CONFIGURATION VALIDATED ONLY**
- **REASONED BUT NOT EXECUTED**

## Mandatory flows

### A. Build

All three images build via `Dockerfile.workload` with tag `phase12`.

### B. Cluster

kind cluster `usagecore-local` with 3 nodes.

### C. Deploy

Helm install; all application pods reach Ready.

### D. Authenticated smoke

Via port-forward + performance seeder:

1. JWT from Keycloak
2. `POST /api/v1/entitlements/check`
3. `POST /api/v1/usage/events` → HTTP 202
4. Outbox → Kafka → consumer → ledger/aggregate
5. `POST /api/v1/usage/consume` where seed data permits

Script: `infrastructure/kubernetes/scripts/smoke.ps1`

### E. Multi-replica Usage Pipeline

2 replicas; verify `usage_ingestion`, `processed_event`, `usage_ledger`, aggregates.

### F. Kafka outage

Scale kafka to 0; PostgreSQL healthy; usage events still 202; readiness stays appropriate; restore; backlog drains.

### G. PostgreSQL outage

Readiness not ready; liveness alive; no false durable 202.

### H. Pending-outbox pod restart

Kafka down → events → PENDING → delete usage-pipeline pod → restore Kafka → single business effect.

Closes Phase 10 **REASONED BUT NOT EXECUTED** gap when live drill succeeds.

### I. Rolling deployment

Bounded traffic during entitlement-runtime rollout; count technical failures honestly.

### J. Scale

`usage-pipeline` replicas 1 → 2 → 3 → 1.

## Optional (mark if unexecuted)

- Consumer pod kill under load
- Quota concurrent load with 2 replicas
- Entitlement rollout under read traffic
- Helm/kubectl rollback
- PostgreSQL pod delete with PVC
- Worker node disruption

## Database verification

After drills, inspect canonical tables:

```sql
SELECT status, count(*) FROM outbox_event GROUP BY status;
SELECT count(*) FROM usage_ingestion;
SELECT count(*) FROM processed_event;
SELECT count(*) FROM usage_ledger;
SELECT meter_key, total FROM usage_aggregate;
```

Use `kubectl exec -n usagecore deploy/postgres -- psql -U usagecore -d usagecore -c '...'`

## Graceful shutdown

`server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s` on all workloads.

Kubernetes `terminationGracePeriodSeconds: 60`.

Usage Pipeline: readiness removes pod from Service endpoints before SIGTERM; Kafka listener stops via Spring lifecycle; outbox rows remain retryable.

## Scripts

```powershell
.\infrastructure\kubernetes\scripts\drills.ps1 -Drill all
```

Individual drills: `kafka-outage`, `postgres-outage`, `pending-outbox-restart`, `scale`, `rollout`.

## Performance lab reuse

Gatling base URLs are configurable via `usagecore.perf.baseUrl.*` system properties. Point at port-forwarded services for rollout experiments; do not change Phase 11 methodology.
