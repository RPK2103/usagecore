# Kubernetes local deployment

Reproducible **kind** cluster + **Helm** chart for UsageCore operability validation.

## Scripts (from repository root)

| Script | Purpose |
| --- | --- |
| `scripts/create-cluster.ps1` | Create kind cluster |
| `scripts/build-images.ps1` | Build three application images |
| `scripts/load-images.ps1` | Load images into kind |
| `scripts/deploy.ps1` | Helm install/upgrade |
| `scripts/smoke.ps1` | Authenticated API smoke |
| `scripts/drills.ps1` | Failure/scale drills |
| `scripts/destroy-cluster.ps1` | Delete cluster |

## Chart

`helm/usagecore` — applications + local PostgreSQL, Kafka, Keycloak.

See [docs/kubernetes/README.md](../../docs/kubernetes/README.md).
