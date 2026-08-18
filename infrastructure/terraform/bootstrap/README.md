# Remote-state bootstrap

Provisions the S3 bucket that `environments/dev` should use **before** a real GitHub-automated `terraform apply`.

This stack is **configuration only** until AWS spend is authorized.

```text
NOT EXECUTED — LIVE COST-BEARING AWS DEPLOYMENT REQUIRES EXPLICIT APPROVAL
```

## Why a separate stack

The state bucket cannot live in the same state that depends on it. First apply of this directory uses a local backend. After the bucket exists, `environments/dev` can switch to:

```hcl
backend "s3" {
  bucket       = "<state_bucket_name>"
  key          = "environments/dev/terraform.tfstate"
  region       = "us-east-1"
  encrypt      = true
  use_lockfile = true
}
```

`use_lockfile = true` is the current Terraform S3 locking mechanism. DynamoDB lock tables are not created here.

## Validate (no AWS)

```powershell
terraform -chdir=infrastructure/terraform/bootstrap init -backend=false
terraform -chdir=infrastructure/terraform/bootstrap validate
```

## Apply

Do not apply unless the user explicitly authorizes AWS spend. The bucket itself is inexpensive compared with EKS/MSK/RDS, but it is still a live AWS resource.
