# Kubernetes (Phase 12)

Local **operability validation** for the three UsageCore workloads on a reproducible **kind** cluster.

This is **not** production HA, cloud deployment, or disaster recovery. See [ADR-021](../adr/ADR-021-kubernetes-packaging-and-operability.md).

## What this proves

- Container images build and run as non-root
- Workloads reach Ready with meaningful probes
- Configuration is externalized (ConfigMap/Secret/env)
- Multi-replica Usage Pipeline preserves business correctness (outbox/inbox/quota)
- Kafka outage does not incorrectly fail HTTP ingestion readiness
- PostgreSQL outage fails readiness, not liveness
- Pod restart with PENDING outbox survives and drains

## What this does not prove

- Multi-AZ / regional failure
- Managed RDS/MSK/EKS
- Production secret management (Kubernetes Secret is base64, local placeholders only)
- Backup/restore or volume destruction recovery
- Zero-downtime production rollouts

## Layout

```text
infrastructure/kubernetes/
  kind-config.yaml          # 1 control-plane + 2 workers
  helm/usagecore/           # Helm chart (apps + local deps)
  scripts/                  # create-cluster, build, deploy, smoke, drills
docs/kubernetes/            # runbooks, failure matrix, validation notes
```

## Quick start (Windows)

Prerequisites: Docker, kubectl, kind, Helm, Java 21 (for seed script).

```powershell
# From repository root
.\infrastructure\kubernetes\scripts\create-cluster.ps1
.\infrastructure\kubernetes\scripts\build-images.ps1 -Tag phase12
.\infrastructure\kubernetes\scripts\load-images.ps1 -Tag phase12
.\infrastructure\kubernetes\scripts\deploy.ps1 -Tag phase12
.\infrastructure\kubernetes\scripts\smoke.ps1
```

## Docker Compose vs Kubernetes

| Environment | Role |
| --- | --- |
| `infrastructure/docker/docker-compose.yml` | Developer dependency stack (host-run JVM apps) |
| kind + Helm | Container orchestration / operability validation |

Compose is **not** removed. Kubernetes adds a parallel validation path.

## Further reading

- [Local cluster](local-cluster.md)
- [Deployment](deployment.md)
- [Operability validation](operability-validation.md)
- [Failure matrix](failure-matrix.md)
