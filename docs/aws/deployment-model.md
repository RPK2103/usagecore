# AWS deployment model

## Responsibility split

| Owner | Owns | Does not own |
| --- | --- | --- |
| Terraform | VPC, EKS, RDS, MSK, ECR, IAM, Secrets Manager resources, security groups | Kubernetes Deployments, Flyway, Kafka topics, business seed data |
| Helm (Phase 12 chart + `values-aws.yaml`) | Deployments, Services, probes, PDBs, ConfigMap keys, Ingress annotations | AWS account resources |
| Phase 14 (deferred) | Image build/push, `terraform apply` automation, Helm release, secret sync | Product domain changes |

Terraform does **not** use the Helm provider to install UsageCore. Infrastructure lifecycle and application release lifecycle stay separable.

## Intended rollout (not automated here)

```text
1. terraform apply (authorized spend) → VPC, EKS, RDS, MSK, ECR, secrets containers
2. Build and push images tagged with git SHA (not latest)
3. Install AWS Load Balancer Controller using the Terraform Pod Identity role
4. Populate Secrets Manager values (RDS managed secret already exists; MSK SCRAM value is operator-written)
5. Sync secrets into Kubernetes Secret `usagecore-secrets` (External Secrets / CSI / deploy-time injection — one mechanism, Phase 14)
6. helm upgrade -f values-aws.yaml with Terraform outputs substituted
7. Control Plane becomes Ready (Flyway runs)
8. Entitlement Runtime and Usage Pipeline start
9. Create Kafka topics (application/platform job, same names as local: usagecore.usage.received.v1 and DLQ)
```

## Flyway / migrations

Control Plane remains the only Flyway owner. Entitlement Runtime and Usage Pipeline must not run migrations.

Cloud Control Plane replica default stays **1** until multi-replica migration coordination is intentionally validated. Flyway advisory locks exist but are not treated as proven production HA for Control Plane.

Forward-only schema: application rollback cannot undo an applied Flyway version. Restore from RDS backup is an operations action, not proven in this phase.

`btree_gist` is created by Flyway `V3`. RDS PostgreSQL 16 supports that extension. Terraform does not `CREATE EXTENSION`.

## Replica model

| Workload | Cloud values-aws default | Caveat |
| --- | --- | --- |
| control-plane | 1 | Migration owner |
| entitlement-runtime | 2 | Stateless reads; not live multi-AZ failover proof |
| usage-pipeline | 2 | Same SKIP LOCKED / consumer group / inbox rules as Phase 12 |

## Images

ECR repositories:

- `usagecore-control-plane`
- `usagecore-entitlement-runtime`
- `usagecore-usage-pipeline`

Tag mutability is **immutable**. Deploy with git SHA. Do not use `latest`.

## Topics

Terraform does not manage `usagecore.usage.received.v1`. Creation remains the existing Helm job (local) or a Phase 14/platform step (AWS).

## Probes and ALB

ALB health checks use `/actuator/health/readiness`.

| Probe | Meaning | AWS implication |
| --- | --- | --- |
| liveness | Process is alive; not Kafka/DB | Do not wire ALB to liveness |
| readiness | PostgreSQL required; Kafka not required on Usage Pipeline | ALB must not send traffic to unready pods |
| startup | Cold JVM | Not an ALB target |

Do not change probes to “full stack health”. That would make a Kafka outage fail HTTP 202 incorrectly.

## Application configuration

Existing env vars, not new names:

| Property | AWS source |
| --- | --- |
| `USAGECORE_DB_URL` | `jdbc:postgresql://<rds_endpoint>:5432/usagecore` |
| `USAGECORE_DB_USERNAME` / `USAGECORE_DB_PASSWORD` | RDS managed Secrets Manager secret, synced into `usagecore-secrets` |
| `USAGECORE_KAFKA_BOOTSTRAP_SERVERS` | MSK SASL/SCRAM bootstrap brokers |
| `USAGECORE_JWK_SET_URI` | External issuer JWKS URL |
| `USAGECORE_OTLP_ENABLED` | Optional; default false in `values-aws.yaml` |

Spring Kafka SASL/SCRAM client settings are **not** in the Java codebase today (local Kafka is plaintext). Enabling MSK SCRAM at runtime requires deploy-time Kafka client configuration. That integration is documented as a Phase 14 application-config boundary, not silently implemented as a domain rewrite.

## Storage

Workloads stay externally stateful (RDS + MSK). No application StatefulSets. EBS CSI is not installed; there is no application PVC on EKS.
