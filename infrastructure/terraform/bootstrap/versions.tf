terraform {
  required_version = ">= 1.11.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Bootstrap uses a local backend. The bucket this stack creates later becomes
  # the S3 backend for environments/dev. Do not store bootstrap state in the
  # bucket it creates on first apply (circular dependency).
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "UsageCore"
      Environment = "bootstrap"
      ManagedBy   = "Terraform"
      Repository  = "UsageCore"
    }
  }
}
