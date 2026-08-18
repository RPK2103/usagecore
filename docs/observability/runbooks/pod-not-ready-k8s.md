# Runbook: Pod not ready (Kubernetes)

Symptom: Application pod stays `Running` but not `Ready`, or Service has no endpoints.

## Check

```powershell
kubectl get pods -n usagecore -o wide
kubectl describe pod <pod-name> -n usagecore
kubectl logs <pod-name> -n usagecore --tail=100
kubectl get events -n usagecore --sort-by=.lastTimestamp
```

## Readiness probe

All workloads: `GET /actuator/health/readiness` includes **PostgreSQL only**.

If readiness fails:

1. Check postgres pod and PVC: `kubectl get pods,pvc -n usagecore -l app.kubernetes.io/name=postgres`
2. Verify Secret/ConfigMap DB URL: `kubectl get configmap usagecore-config -n usagecore -o yaml`
3. Control Plane must complete Flyway before steady readiness on first deploy

Usage Pipeline: Kafka down **should not** fail readiness.

## Startup probe

Cold JVM may take several minutes. Startup probe allows up to ~300s before liveness kills the pod.

## Rollout stuck

```powershell
kubectl rollout status deployment/usagecore-usage-pipeline -n usagecore
kubectl rollout undo deployment/usagecore-usage-pipeline -n usagecore
```

## Migration note

Only Control Plane runs Flyway. If schema migration fails, inspect control-plane logs first.
