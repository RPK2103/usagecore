# Deployment matrix

Do not conflate these environments. They prove different things.

| Environment / mode | What runs | Purpose | Evidence level |
| --- | --- | --- | --- |
| Docker Compose | PostgreSQL, Keycloak, Kafka, Prometheus, Grafana, OTel Collector. JVM apps on the host. | Developer workflow and Phase 11 lab dependencies | **VERIFIED BY CONTAINER BUILD** of the Compose file (`docker compose … config`). Runtime depends on the local machine. |
| Maven `clean verify` + Testcontainers | Ephemeral PostgreSQL and Kafka per integration/resilience tests | Correctness, concurrency, failure injection | **VERIFIED BY TEST** |
| Performance lab (`performance/`) | Gatling against host-run JARs + Compose | Local measurement methodology | **VERIFIED BY LIVE PERFORMANCE RUN** on a documented laptop. Not cloud capacity. |
| kind + Helm | Three workload images + in-cluster PostgreSQL/Kafka/Keycloak | Kubernetes operability | **VERIFIED BY LIVE KUBERNETES SMOKE** and **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** (Phase 12) |
| AWS / Terraform | VPC, EKS, RDS, MSK, ECR, IAM, Secrets Manager **as code** | Target cloud architecture | **VERIFIED BY TERRAFORM VALIDATE** / fmt. **CONFIGURATION VALIDATED ONLY** for runtime AWS. No default `terraform apply`. |
| Helm `values-aws.yaml` | Same chart; in-cluster Postgres/Kafka/Keycloak disabled | Overlay for EKS + RDS + MSK | **VERIFIED BY HELM LINT** / template. Not a live EKS deploy. |
| GitHub Actions | CI, security, container, terraform, deploy workflows | PR gates and gated delivery design | First hosted run on `24975ee`: Terraform + Container **success**; CI Java/Verify **failed** (`mvnw` not executable); Security Trivy IaC **failed** on documented example findings. ECR/OIDC/deploy **not executed**. See [cicd/evidence.md](../cicd/evidence.md). |

`performance/` is an engineering lab, not a fourth runtime service.

Terraform is infrastructure code, not a runtime.

`libraries/database-migrations` and `libraries/event-contracts` are shared artifacts, not deployable services.
