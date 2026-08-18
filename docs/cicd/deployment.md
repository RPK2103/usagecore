# Deployment

## Terraform vs Helm

Terraform owns AWS (VPC, EKS, RDS, MSK, ECR, IAM, OIDC roles, Secrets Manager containers). Helm owns Kubernetes objects. Deploy jobs must not recreate Deployments with raw `kubectl`/`aws` except for the `usagecore-secrets` Secret.

## GitHub environment

Deploy jobs use environment `dev`. Phase 13/14 ship only the Terraform `dev` root. There is no staging/production pipeline.

## Sequence (when AWS exists)

1. Infrastructure already compatible, or gated `terraform apply`
2. `aws eks update-kubeconfig` with the Helm deploy role (short-lived OIDC credentials; kubeconfig is not a GitHub secret)
3. Optional AWS Load Balancer Controller install (`scripts/ci/install-alb-controller.sh`) — not inside the application chart. Terraform owns the Pod Identity association; the script does not pass the role as an IRSA annotation.
4. Sync RDS + MSK secrets into `usagecore-secrets`
5. Helm upgrade Control Plane only (Flyway). The chart hook `kafka-topics-init` creates MSK topics from inside the cluster (GitHub-hosted runners cannot reach private MSK).
6. Wait until Control Plane is Available
7. Helm upgrade remaining workloads
8. Authenticated smoke

## Secrets

| Secret | Source | Kubernetes |
| --- | --- | --- |
| DB username/password | RDS managed Secrets Manager | `USAGECORE_DB_USERNAME` / `USAGECORE_DB_PASSWORD` |
| MSK SCRAM | Operator-written Secrets Manager value | `USAGECORE_KAFKA_SASL_JAAS_CONFIG` |
| JWKS URL | Non-secret `OIDC_JWK_SET_URI` | ConfigMap |

Mechanism: deploy job with OIDC retrieves secrets and `kubectl apply`s a generated Secret. External Secrets Operator and CSI driver are not installed.

Limitations: the job principal can read those two secrets; temp files exist only for the step; values must not appear in logs. This is a dev-milestone pattern, not a multi-tenant secret platform.

## MSK runtime

Usage Pipeline AWS profile (`SPRING_PROFILES_ACTIVE=aws` via Helm when `config.kafka.sasl.enabled`):

- `security.protocol=SASL_SSL`
- `sasl.mechanism=SCRAM-SHA-512`
- `sasl.jaas.config` from the Secret

Local Compose/kind stay PLAINTEXT (`application.yml` defaults). Do not enable the aws profile locally.

RDS JDBC URL from Helm `values-aws.yaml` uses `sslmode=require` (`config.dbSslMode`), matching the Terraform `rds_jdbc_url` output. Credentials still come from Secrets Manager, not that URL.

## EKS authentication

IAM: Helm deploy role may call `eks:DescribeCluster` / `eks:AccessKubernetesApi`.

Kubernetes: EKS access entry + namespaced `AmazonEKSAdminPolicy` for `usagecore` and `kube-system` (controller bootstrap). That is not cluster-admin for every namespace.

## Helm command shape

`helm upgrade --install usagecore ... --wait --timeout 10m` with `values-aws.yaml` and immutable image repository/tag overrides. `--force` is not used.

Release metadata annotations (`usagecore.io/git-sha`, `usagecore.io/image-tag`) are not selector labels.

## Post-deploy smoke

`scripts/ci/post-deploy-smoke.sh` requires entitlement HTTP 200, usage event HTTP 202, and consume HTTP 200/409. `/actuator/health` is not sufficient.

Smoke identity comes from the external OIDC issuer. It is not Keycloak-in-cluster.

## Concurrency

Workflow concurrency group `usagecore-dev-deploy` with `cancel-in-progress: false`. In-flight Terraform apply is not cancelled.
