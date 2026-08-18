# Kubernetes deployment

## Helm chart

Chart path: `infrastructure/kubernetes/helm/usagecore`

Helm reduces duplication across three application workloads while keeping templates readable. Raw manifests for each dependency (postgres, kafka, keycloak) remain separate template files inside the chart.

## Workloads

| Deployment | Replicas (local) | Service | Port |
| --- | --- | --- | --- |
| `usagecore-control-plane` | 1 | `usagecore-control-plane` | 8080 |
| `usagecore-entitlement-runtime` | 2 | `usagecore-entitlement-runtime` | 8082 |
| `usagecore-usage-pipeline` | 2 | `usagecore-usage-pipeline` | 8083 |

Dependencies (when enabled in values): `postgres`, `kafka`, `keycloak`.

## Build images

Shared multi-stage Dockerfile: `infrastructure/docker/Dockerfile.workload`

```powershell
docker build -f infrastructure/docker/Dockerfile.workload `
  --build-arg WORKLOAD=control-plane --build-arg SERVER_PORT=8080 `
  -t usagecore/control-plane:phase12 .

docker build -f infrastructure/docker/Dockerfile.workload `
  --build-arg WORKLOAD=entitlement-runtime --build-arg SERVER_PORT=8082 `
  -t usagecore/entitlement-runtime:phase12 .

docker build -f infrastructure/docker/Dockerfile.workload `
  --build-arg WORKLOAD=usage-pipeline --build-arg SERVER_PORT=8083 `
  -t usagecore/usage-pipeline:phase12 .
```

Or: `.\infrastructure\kubernetes\scripts\build-images.ps1 -Tag phase12`

Runtime: Eclipse Temurin 21 JRE, user `10001`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`.

## Deploy

```powershell
helm lint infrastructure/kubernetes/helm/usagecore
helm template usagecore infrastructure/kubernetes/helm/usagecore --namespace usagecore
.\infrastructure\kubernetes\scripts\deploy.ps1 -Tag phase12
```

## Configuration

| Source | Examples |
| --- | --- |
| ConfigMap `usagecore-config` | `USAGECORE_DB_URL`, `USAGECORE_JWK_SET_URI`, `USAGECORE_KAFKA_BOOTSTRAP_SERVERS` |
| Secret `usagecore-secrets` | DB username/password (local placeholders) |
| Pod env | `SERVER_PORT` |

Spring binds standard env vars (`USAGECORE_*`) already used by Compose/host runs.

## Flyway / migration ownership

**Control Plane only** runs Flyway (`spring.flyway.enabled=true`). Entitlement Runtime and Usage Pipeline have Flyway disabled.

Local chart uses **1 Control Plane replica** to avoid concurrent migration ambiguity. Flyway uses PostgreSQL advisory locking if multiple replicas start migrations, but Phase 12 does not treat multi-replica Control Plane migration as a validated production pattern.

## Probes

| Probe | Path | Notes |
| --- | --- | --- |
| startup | `/actuator/health/liveness` | Up to ~300s for cold JVM |
| liveness | `/actuator/health/liveness` | `livenessState,ping` — not DB/Kafka |
| readiness | `/actuator/health/readiness` | `readinessState,db` — Usage Pipeline excludes Kafka |

## Resources (local only)

Per Java app (not production sizing):

| | CPU request | CPU limit | Memory request | Memory limit |
| --- | --- | --- | --- | --- |
| Apps | 250m | 1 | 512Mi | 768Mi |

JVM heap is ~75% of container memory limit via `MaxRAMPercentage`; leave headroom for metaspace/native threads.

## Rollback

```powershell
kubectl rollout history deployment/usagecore-usage-pipeline -n usagecore
kubectl rollout undo deployment/usagecore-usage-pipeline -n usagecore
helm rollback usagecore
```

Application rollback ≠ database schema rollback (Flyway is forward-only).

## Observability

Pods expose `/actuator/prometheus`. Phase 12 uses **external** Compose Prometheus/Grafana optionally via port-forward; in-cluster Prometheus was not required for validation scope.

## Health debugging

```powershell
kubectl get pods -n usagecore
kubectl describe pod <name> -n usagecore
kubectl logs <name> -n usagecore
kubectl get events -n usagecore --sort-by=.lastTimestamp
kubectl get endpoints -n usagecore
```
