variable "repository_names" {
  type        = list(string)
  description = "Exactly the three UsageCore runtime images."
  default = [
    "usagecore-control-plane",
    "usagecore-entitlement-runtime",
    "usagecore-usage-pipeline",
  ]

  validation {
    condition     = length(var.repository_names) == 3
    error_message = "Create repositories for the three workloads only. Do not add a fourth runtime image."
  }
}

variable "lifecycle_tagged_image_count" {
  type        = number
  description = "Retain this many tagged images per repository."
  default     = 20
}

variable "untagged_image_expire_days" {
  type    = number
  default = 7
}

variable "force_delete" {
  type        = bool
  description = "Allow terraform destroy to delete repositories that still contain images. Dev default true; production should be false."
  default     = true
}

variable "tags" {
  type = map(string)
}
