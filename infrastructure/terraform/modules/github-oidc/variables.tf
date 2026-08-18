variable "name_prefix" {
  type        = string
  description = "Resource name prefix, typically usagecore-dev."
}

variable "github_repository" {
  type        = string
  description = "GitHub repository in owner/name form. Do not hard-code a personal username in workflows; pass it here."

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name."
  }
}

variable "github_environment" {
  type        = string
  description = "GitHub Environment name used in OIDC subject claims for apply and deploy roles."
  default     = "dev"
}

variable "create_oidc_provider" {
  type        = bool
  description = "Create the account-level GitHub OIDC provider. Set false and look up the existing provider if one already exists in the account."
  default     = true
}

variable "eks_cluster_name" {
  type        = string
  description = "EKS cluster name for access entries bound to the Helm deploy role."
}

variable "kubernetes_namespace" {
  type        = string
  description = "Application namespace granted to the Helm deploy role."
  default     = "usagecore"
}

variable "ecr_repository_arns" {
  type        = list(string)
  description = "ARNs of the three UsageCore ECR repositories."
}

variable "state_bucket_arn" {
  type        = string
  description = "Optional remote-state S3 bucket ARN. Empty until bootstrap is applied."
  default     = ""
}

variable "state_key_prefix" {
  type        = string
  description = "S3 key prefix for Terraform state objects this environment may read/write."
  default     = "environments/dev/"
}

variable "tags" {
  type = map(string)
}
