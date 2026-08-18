variable "aws_region" {
  type        = string
  description = "AWS region for this example environment. This is a configurable default, not a claimed live deployment region."
  default     = "us-east-1"
}

variable "environment" {
  type        = string
  description = "Environment name. Phase 13 ships only this dev root; additional roots can be added later."
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging, or prod."
  }
}

variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR. Example 10.20.0.0/16 is a private range, not a home-network CIDR."
  default     = "10.20.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR."
  }
}

variable "az_count" {
  type        = number
  description = "Number of AZs when availability_zones is empty."
  default     = 3

  validation {
    condition     = var.az_count == 2 || var.az_count == 3
    error_message = "az_count must be 2 or 3."
  }
}

variable "availability_zones" {
  type        = list(string)
  description = "Optional explicit AZ names. Empty selects the first az_count AZs in the region."
  default     = []
}

variable "nat_gateway_strategy" {
  type        = string
  description = "single is the cost-conscious dev default. per_az is the production-oriented HA option."
  default     = "single"

  validation {
    condition     = contains(["single", "per_az"], var.nat_gateway_strategy)
    error_message = "nat_gateway_strategy must be single or per_az."
  }
}

variable "eks_cluster_name" {
  type    = string
  default = "usagecore-dev"
}

variable "kubernetes_version" {
  type        = string
  description = "Must be an AWS-supported EKS version at apply time. 1.34 was in standard support as of 2026-08-18; re-check before deploying."
  default     = "1.34"

  validation {
    condition     = can(regex("^1\\.[0-9]+$", var.kubernetes_version))
    error_message = "kubernetes_version must look like 1.34."
  }
}

variable "eks_endpoint_public_access" {
  type        = bool
  description = "Public EKS API with restricted CIDRs is the documented dev convenience trade-off versus a fully private control plane."
  default     = true
}

variable "eks_public_access_cidrs" {
  type        = list(string)
  description = "Admin CIDRs for the public EKS endpoint. Default is RFC 5737 TEST-NET-3 and must be replaced with a real operator CIDR before apply."
  default     = ["203.0.113.0/24"]
}

variable "eks_allow_public_endpoint_from_anywhere" {
  type        = bool
  description = "Explicit opt-in if EKS public API must use 0.0.0.0/0. Default false."
  default     = false
}

variable "node_instance_types" {
  type        = list(string)
  description = "Dev/example node types. Not derived from Phase 11 laptop benchmarks."
  default     = ["t3.large"]
}

variable "node_desired_size" {
  type    = number
  default = 2
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  type    = number
  default = 3
}

variable "node_disk_size" {
  type    = number
  default = 20
}

variable "rds_engine_version" {
  type        = string
  description = "PostgreSQL 16 family to match Testcontainers/local Compose. Apply may require a specific minor AWS currently offers."
  default     = "16"
}

variable "rds_instance_class" {
  type        = string
  description = "Dev/example class, not production sizing."
  default     = "db.t4g.medium"
}

variable "rds_allocated_storage" {
  type    = number
  default = 20
}

variable "rds_multi_az" {
  type        = bool
  description = "Dev default is not HA. Production recommendation is true."
  default     = false
}

variable "rds_backup_retention_period" {
  type    = number
  default = 7
}

variable "rds_deletion_protection" {
  type        = bool
  description = "False keeps terraform destroy practical for the example environment. Production should be true."
  default     = false
}

variable "rds_skip_final_snapshot" {
  type        = bool
  description = "True keeps terraform destroy practical for the example environment. Production should be false."
  default     = true
}

variable "msk_kafka_version" {
  type    = string
  default = "3.8.x"
}

variable "msk_broker_instance_type" {
  type        = string
  description = "Dev/example broker class. MSK is a major cost driver."
  default     = "kafka.t3.small"
}

variable "msk_broker_count" {
  type        = number
  description = "Two brokers in two of the data subnets. Three brokers would span three AZs at higher cost."
  default     = 2

  validation {
    condition     = var.msk_broker_count >= 2
    error_message = "msk_broker_count must be at least 2."
  }
}

variable "msk_broker_volume_size" {
  type    = number
  default = 20
}

variable "msk_associate_scram_secret" {
  type        = bool
  description = "Associate the Secrets Manager SCRAM secret only after an operator has written the secret value. Default false."
  default     = false
}

variable "ecr_force_delete" {
  type        = bool
  description = "Allow destroy of non-empty ECR repositories in this example environment."
  default     = true
}

variable "alb_http_enabled" {
  type        = bool
  description = "Allow TCP 80 on the ALB security group when ACM certificates are not available."
  default     = true
}

variable "alb_https_enabled" {
  type        = bool
  description = "Allow TCP 443 on the ALB security group. Set certificate ARN in Helm values separately."
  default     = true
}

variable "alb_ingress_cidrs" {
  type    = list(string)
  default = ["0.0.0.0/0"]
}

variable "acm_certificate_arn" {
  type        = string
  description = "Optional ACM certificate ARN for HTTPS at the ALB. Empty means Helm should not assume TLS."
  default     = ""
}

variable "image_tag" {
  type        = string
  description = "Immutable image tag (git SHA) for Helm values-aws.yaml. Not latest."
  default     = "REPLACE_WITH_GIT_SHA"
}
