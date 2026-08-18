# CI/CD

Phase 14 delivery system for UsageCore. GitHub Actions is the only CI/CD platform.

```text
CONFIGURATION VALIDATED ONLY
```

unless a workflow actually ran on GitHub. This repository implements the pipelines; live GitHub/AWS execution is documented per check.

## Documents

| Doc | Contents |
| --- | --- |
| [pipeline.md](pipeline.md) | PR → main → artifact → gated deploy |
| [security.md](security.md) | Permissions, OIDC, scanners, finding policy |
| [security-matrix.md](security-matrix.md) | Workflow trigger/trust audit |
| [deployment.md](deployment.md) | Helm, secrets, MSK, smoke, environments |
| [rollback.md](rollback.md) | Helm vs schema vs Terraform |
| [evidence.md](evidence.md) | What is proven vs unexecuted |

ADR: [ADR-023](../adr/ADR-023-github-actions-ci-cd-and-supply-chain-security.md)

## Workflows

| Workflow | File | Ordinary PR | Trusted main | Cloud mutation |
| --- | --- | --- | --- | --- |
| CI | `.github/workflows/ci.yml` | yes | yes | no |
| Security | `.github/workflows/security.yml` | yes | yes | no |
| Container | `.github/workflows/container.yml` | build/scan only | build + optional ECR | ECR push if OIDC configured |
| Terraform | `.github/workflows/terraform.yml` | fmt/validate | fmt/validate | plan on dispatch only |
| Deploy | `.github/workflows/deploy.yml` | no | no | gated `dev` environment |

## Commands that run without AWS

```powershell
.\mvnw.cmd clean verify
terraform fmt -check -recursive infrastructure/terraform
terraform -chdir=infrastructure/terraform/environments/dev init -backend=false
terraform -chdir=infrastructure/terraform/environments/dev validate
helm lint infrastructure/kubernetes/helm/usagecore
docker compose -f infrastructure/docker/docker-compose.yml config --quiet
```

Unix CI uses `./mvnw clean verify` on GitHub-hosted Linux.

## Repository variables (non-secret)

Configure after Terraform apply. Do not store DB/Kafka passwords here.

`AWS_REGION` · `AWS_ROLE_ECR_PUBLISH` · `AWS_ROLE_TERRAFORM_PLAN` · `AWS_ROLE_TERRAFORM_APPLY` · `AWS_ROLE_HELM_DEPLOY` · `EKS_CLUSTER_NAME` · `RDS_ENDPOINT` · `RDS_MASTER_USER_SECRET_ARN` · `MSK_BOOTSTRAP_BROKERS` · `MSK_SCRAM_SECRET_ARN` · `OIDC_JWK_SET_URI` · `ALB_SECURITY_GROUP_ID` · `ALB_CONTROLLER_ROLE_ARN` · `VPC_ID` · smoke URL/client/username

Smoke password, if used, is a GitHub Environment secret (`SMOKE_PASSWORD`), not an AWS access key.
