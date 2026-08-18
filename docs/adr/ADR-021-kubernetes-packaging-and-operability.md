# ADR-021: Kubernetes packaging and operability

## Status

Accepted — Phase 12 local validation milestone.

## Context

UsageCore has three deployable Java workloads proven on host JVM and Testcontainers. Phase 12 must show the same commercial and distributed-system invariants hold when applications are containerized, replicated, restarted, and rolled on Kubernetes—without AWS, CI/CD, or a fourth service.

## Decision

### Local cluster: kind

Use **kind** (not k3d, not Docker Desktop K8s as the primary path):

- Reproducible multi-node topology (`1 control-plane + 2 workers`)
- `kind load docker-image` avoids a registry for local validation
- Standard upstream Kubernetes API behavior

### Packaging: Helm chart

Single chart `infrastructure/kubernetes/helm/usagecore` with understandable templates—not a generic enterprise abstraction. Values drive image tags, replicas, and local dependency toggles.

Raw manifest clarity is preserved per component file (postgres, kafka, keycloak, each app).

### Container images

Multi-stage `Dockerfile.workload`:

- Build: Temurin 21 JDK + Maven wrapper → fat JAR
- Runtime: Temurin 21 JRE, non-root UID 10001, no Maven/source in final layer
- Tags: deterministic (`phase12`, git SHA)—not `:latest` alone

### Dependencies in-cluster

Lightweight Deployments for PostgreSQL (PVC), single-broker KRaft Kafka, Keycloak (realm ConfigMap). **No** Postgres operator, Strimzi, or service mesh.

Honest scope: single PG/Kafka pods are **not** production HA.

### Replica strategy

| Workload | Local replicas | Rationale |
| --- | --- | --- |
| control-plane | 1 | Flyway migration owner; avoid unvalidated multi-replica migration ops |
| entitlement-runtime | 2 | Stateless read path; rollout under traffic |
| usage-pipeline | 2+ | Prove outbox SKIP LOCKED + consumer group + inbox dedupe |

### Flyway ownership

Only Control Plane enables Flyway. Flyway advisory locks may serialize concurrent migrations, but Phase 12 documents **single replica** as the local safe default. No migration Job added—unnecessary for this milestone.

### Configuration and secrets

- **ConfigMap**: non-secret env (`USAGECORE_DB_URL`, JWKS URI, Kafka bootstrap)
- **Secret**: local placeholder DB/Keycloak credentials only

Kubernetes Secret is **not** production secret management (Phase 13 AWS/Terraform).

No Vault/External Secrets Operator.

### Probes

Align with Phase 9A semantics:

| Probe | Endpoint | Usage Pipeline note |
| --- | --- | --- |
| startup/liveness | `/actuator/health/liveness` | Does not require DB/Kafka |
| readiness | `/actuator/health/readiness` | DB only; Kafka health disabled in app config |

### Graceful termination

- `server.shutdown=graceful`, 30s shutdown phase
- `terminationGracePeriodSeconds: 60`
- Usage Pipeline: outbox remains retryable; at-least-once transport preserved

### Security context (applications)

- `runAsNonRoot: true`, UID 10001
- `allowPrivilegeEscalation: false`, drop ALL capabilities
- `seccompProfile: RuntimeDefault`
- `automountServiceAccountToken: false`
- No RBAC for app pods
- `readOnlyRootFilesystem` not enabled (Spring Boot temp dirs)

### Resources

Conservative local requests/limits; `MaxRAMPercentage=75`. **Not** production sizing.

### Observability

External Compose Prometheus/Grafana acceptable; in-cluster observability optional. Pods expose `/actuator/prometheus`.

### Explicit non-goals (Phase 12)

- AWS/EKS, Terraform, CI/CD pipelines
- HPA/VPA/KEDA (manual scale sufficient)
- Istio/Linkerd
- Kafka/Postgres operators
- NetworkPolicy enforcement proof (deferred / config-only)
- Disaster recovery, backup/restore, multi-region

## Consequences

- Docker Compose remains the developer dependency stack; Kubernetes is operability validation.
- Phase 13 can map the same chart values to EKS + RDS + MSK + IRSA.
- Live failure drills produce evidence in `docs/kubernetes/failure-matrix.md`.
- Application code changes limited to graceful shutdown configuration.

## What local Kubernetes proves

Containers start, configure externally, probe correctly, scale horizontally without breaking inbox/outbox/quota invariants, survive pod restart with durable outbox, and roll with bounded disruption.

## What it does not prove

Production HA, managed infrastructure, secret rotation, autoscaling policy, zero-downtime globally, or disaster recovery.
