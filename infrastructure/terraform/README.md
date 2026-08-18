# Terraform (Phase 13)

Infrastructure as code for a **minimal AWS topology** that hosts the existing three UsageCore workloads.

This directory is **configuration**. It is not evidence of a live AWS deployment.

```text
CONFIGURATION VALIDATED ONLY unless terraform plan/apply is actually executed.
terraform apply is cost-bearing and must not be run without explicit approval.
```

## Layout

```text
infrastructure/terraform/
  environments/dev/     # single example environment root
  modules/
    network/            # VPC, subnets, NAT, routing, S3 gateway endpoint, security groups
    eks/                # cluster, managed node group, add-ons, cluster/node IAM
    rds/                # RDS PostgreSQL
    msk/                # provisioned Amazon MSK
    ecr/                # three workload repositories
    iam/                # AWS Load Balancer Controller Pod Identity
    secrets/            # Secrets Manager containers (values not written by Terraform)
```

Additional environments can be added later by copying `environments/dev` and changing variables. Phase 13 does not ship empty qa/staging/prod copies.

## Requirements

- Terraform `>= 1.11.0, < 2.0.0` (1.11+ supports S3 native state locking documented for a future backend)
- AWS provider `~> 6.0`
- AWS credentials via the standard chain (`AWS_PROFILE`, environment variables, or instance role)
- Do **not** put access keys in Terraform files

## Validate configuration (no AWS spend)

From the repository root:

```powershell
terraform -chdir=infrastructure/terraform/environments/dev fmt -recursive ..\..\
terraform -chdir=infrastructure/terraform/environments/dev init -backend=false
terraform -chdir=infrastructure/terraform/environments/dev validate
```

Or from the environment directory:

```powershell
cd infrastructure/terraform/environments/dev
terraform init -backend=false
terraform fmt -check
terraform validate
```

`init -backend=false` still downloads providers. It does not create AWS resources.

## Plan / apply (credentials required; apply incurs cost)

```powershell
cd infrastructure/terraform/environments/dev
copy terraform.tfvars.example terraform.tfvars
# replace placeholders, especially eks_public_access_cidrs
terraform init
terraform plan
```

```text
terraform apply
```

creates chargeable resources (EKS, NAT Gateway, RDS, MSK, EC2 nodes, ALB after controller install). **Do not apply unless spend is explicitly approved.**

`terraform destroy` deletes the example environment, including database storage. It is not a casual command. RDS deletion protection, final snapshots, leftover ALB ENIs, and non-empty ECR repositories can block destroy depending on settings.

## Backend

The example root uses the **local** backend so validation does not require bootstrap resources.

A future CI/production backend should be a **separate bootstrap**:

- Encrypted S3 bucket
- Bucket versioning
- Restricted IAM
- `use_lockfile = true` (native S3 locking)

Do not revive DynamoDB locking; current Terraform S3 backend docs mark it deprecated.

Terraform state can contain secret ARNs, resource identifiers, and (for some patterns) secret material. Secrets Manager does **not** keep secrets out of state by itself. RDS `manage_master_user_password` avoids putting the master password in configuration and outputs; the managed secret ARN can still appear in state.

## Mapping outputs to the application

| Terraform output | Application / Helm |
| --- | --- |
| `rds_jdbc_url` | `USAGECORE_DB_URL` / `config.dbHost` |
| RDS managed secret | `USAGECORE_DB_USERNAME` / `USAGECORE_DB_PASSWORD` (sync at deploy time) |
| `msk_bootstrap_brokers_sasl_scram` | `USAGECORE_KAFKA_BOOTSTRAP_SERVERS` |
| External IdP JWKS | `USAGECORE_JWK_SET_URI` |
| `ecr_repository_urls` | `images.*.repository` |
| `helm_image_tag` | `images.*.tag` (git SHA) |
| `alb_security_group_id` | Ingress annotation |
| `acm_certificate_arn` | Ingress TLS, optional |

Helm overlay: [`../../kubernetes/helm/usagecore/values-aws.yaml`](../../kubernetes/helm/usagecore/values-aws.yaml)

Terraform does not install the application, run Flyway, or create Kafka topics.

## What this does not do

- Live `terraform apply`
- GitHub Actions / CI
- Helm provider app install
- Route 53 hosted zones
- Seed tenants, contracts, or usage data
- LocalStack as a substitute for AWS
