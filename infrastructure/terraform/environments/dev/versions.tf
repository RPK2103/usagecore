terraform {
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Local backend so fmt/validate work without AWS.
  #
  # Before a real automated apply, copy backend-s3.hcl.example and run:
  #   terraform init -backend-config=backend-s3.hcl
  #
  # Native S3 lock files (use_lockfile = true) replace DynamoDB locking.
  # The state bucket is provisioned by infrastructure/terraform/bootstrap,
  # which is a separate stack to avoid a circular dependency.
}
