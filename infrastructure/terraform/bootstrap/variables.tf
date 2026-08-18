variable "aws_region" {
  type        = string
  description = "AWS region for the remote-state bucket."
  default     = "us-east-1"
}

variable "state_bucket_name" {
  type        = string
  description = "Globally unique S3 bucket name for Terraform state. Replace before apply."
  default     = "usagecore-tfstate-example-replace-me"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid S3 bucket name."
  }
}

variable "force_destroy" {
  type        = bool
  description = "Allow destroy of a non-empty state bucket. Default false."
  default     = false
}
