# Local kind cluster

## Choice: kind

[ADR-021](../adr/ADR-021-kubernetes-packaging-and-operability.md) records why **kind** was chosen over k3d: upstream Kubernetes conformance, simple multi-node topology, and `kind load docker-image` for local images without a registry.

Docker Desktop Kubernetes may work with the same manifests but is **not** the primary reproducible path.

## Topology

`infrastructure/kubernetes/kind-config.yaml`:

| Node | Role |
| --- | --- |
| 1 | control-plane |
| 2 | worker |
| 3 | worker |

Preferred pod anti-affinity for entitlement-runtime and usage-pipeline uses `kubernetes.io/hostname`. On a constrained machine, reduce workers in the config file and document the limitation.

## Create / destroy

```powershell
.\infrastructure\kubernetes\scripts\create-cluster.ps1
kubectl cluster-info --context kind-usagecore-local
.\infrastructure\kubernetes\scripts\destroy-cluster.ps1
```

## Image loading

kind nodes do not share the host Docker daemon's image cache for pulled images built locally:

```powershell
docker build ... -t usagecore/control-plane:phase12 .
kind load docker-image usagecore/control-plane:phase12 --name usagecore-local
```

Use deterministic tags (`phase12`, git SHA). Do **not** rely on `:latest` alone.

## Access

Internal services use ClusterIP. For local API calls:

```powershell
kubectl port-forward -n usagecore svc/usagecore-entitlement-runtime 8082:8082
kubectl port-forward -n usagecore svc/usagecore-usage-pipeline 8083:8083
kubectl port-forward -n usagecore svc/keycloak 8081:8081
```

Ingress was not required for Phase 12 validation.

## Namespace

All resources deploy into `usagecore` (not `default`).

## Limitations

- Single PostgreSQL pod + PVC ≠ disaster recovery
- Single Kafka broker ≠ production durability (outbox in PostgreSQL remains authoritative)
- Local Secrets are placeholders
- Cluster deletion destroys kind node state (PVC behavior depends on storage class)
