terraform {
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Local backend for Phase 13 validation.
  #
  # A future CI/production backend should use encrypted S3 with native lock files:
  #
  #   backend "s3" {
  #     bucket       = "example-usagecore-tfstate"
  #     key          = "environments/dev/terraform.tfstate"
  #     region       = "us-east-1"
  #     encrypt      = true
  #     use_lockfile = true
  #   }
  #
  # DynamoDB-based locking is deprecated in current Terraform S3 backend docs.
  # Do not provision that backend in this stack (bootstrap circular dependency).
}
