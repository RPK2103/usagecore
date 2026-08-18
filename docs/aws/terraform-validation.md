# Terraform validation

## Commands

From `infrastructure/terraform/environments/dev`:

```powershell
terraform fmt -recursive ../..
terraform fmt -check -recursive ../..
terraform init -backend=false
terraform validate
```

`terraform plan` requires AWS credentials and is **non-mutating**. It is still not a live deployment.

`terraform apply` is cost-bearing and is not part of Phase 13 unless explicitly authorized.

## Evidence labels

| Result | Label |
| --- | --- |
| `terraform fmt -check` succeeds | VERIFIED BY TERRAFORM FMT |
| `terraform validate` succeeds | VERIFIED BY TERRAFORM VALIDATE |
| `terraform plan` against a real account succeeds | VERIFIED BY TERRAFORM PLAN |
| No plan run | NOT EXECUTED — AWS CREDENTIALS NOT AVAILABLE |
| Helm lint/template of `values-aws.yaml` | VERIFIED BY HELM LINT / VERIFIED BY KUBERNETES CONFIGURATION |
| Live cluster in AWS | VERIFIED BY AWS LIVE DEPLOYMENT — not claimed unless apply happened |

`CONFIGURATION VALIDATED ONLY` applies to AWS runtime behavior (failover, logs ingested, ALB serving traffic) when only fmt/validate ran.

## What validate proves

- HCL is syntactically valid
- Provider schemas accept the resources
- Required variables/modules resolve
- Some `validation {}` blocks hold for defaults

## What validate does not prove

- AWS API acceptance of every argument (engine minor, MSK version, EKS version in a given region)
- IAM policy correctness at runtime
- Network connectivity
- Cost
- That `btree_gist` exists on the chosen RDS minor (documented from AWS extension lists; Flyway still creates it)

TFLint / tfsec / Checkov are not required for Phase 13. They may appear in Phase 14 CI.

LocalStack is not used as AWS proof.
