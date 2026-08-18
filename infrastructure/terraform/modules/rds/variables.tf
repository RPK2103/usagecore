variable "name_prefix" {
  type        = string
  description = "Resource name prefix."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private data subnet IDs for the DB subnet group."
}

variable "security_group_ids" {
  type        = list(string)
  description = "Security groups attached to the RDS instance."
}

variable "engine_version" {
  type        = string
  description = "RDS PostgreSQL engine version. Keep major version 16 unless AWS support requires a specific minor."
  default     = "16"
}

variable "instance_class" {
  type        = string
  description = "Dev/example instance class, not production sizing."
  default     = "db.t4g.medium"
}

variable "allocated_storage" {
  type    = number
  default = 20
}

variable "max_allocated_storage" {
  type        = number
  description = "Storage autoscaling ceiling in GiB. 0 disables autoscaling."
  default     = 50
}

variable "storage_type" {
  type    = string
  default = "gp3"
}

variable "db_name" {
  type    = string
  default = "usagecore"
}

variable "username" {
  type    = string
  default = "usagecore"
}

variable "multi_az" {
  type        = bool
  description = "Dev default is false (not HA). Production should enable Multi-AZ."
  default     = false
}

variable "backup_retention_period" {
  type        = number
  description = "Automated backup retention in days. Non-zero enables backups; restoration is not proven by this configuration."
  default     = 7
}

variable "deletion_protection" {
  type        = bool
  description = "Dev default false so terraform destroy remains practical. Production should be true."
  default     = false
}

variable "skip_final_snapshot" {
  type        = bool
  description = "Dev default true so destroy does not require a snapshot. Production should be false."
  default     = true
}

variable "tags" {
  type = map(string)
}
