variable "name_prefix" {
  type        = string
  description = "Resource name prefix."
}

variable "cluster_name" {
  type        = string
  description = "EKS cluster name."
}

variable "kubernetes_version" {
  type        = string
  description = "EKS Kubernetes version. Must be selected from currently AWS-supported versions before apply. Example default is a currently standard-support minor, not a guarantee it remains supported."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private application subnet IDs for the control plane and managed node group."
}

variable "additional_security_group_ids" {
  type        = list(string)
  description = "Extra security groups attached to managed nodes (application traffic)."
  default     = []
}

variable "endpoint_private_access" {
  type        = bool
  description = "Enable the private EKS API endpoint."
  default     = true
}

variable "endpoint_public_access" {
  type        = bool
  description = "Enable the public EKS API endpoint. Dev convenience; fully private access needs extra operator infrastructure."
  default     = true
}

variable "public_access_cidrs" {
  type        = list(string)
  description = "CIDRs allowed to reach a public EKS API endpoint. Do not default this to 0.0.0.0/0."

  validation {
    condition     = length(var.public_access_cidrs) > 0 && alltrue([for cidr in var.public_access_cidrs : can(cidrhost(cidr, 0))])
    error_message = "Provide at least one valid IPv4 CIDR for EKS public API access."
  }
}

variable "enabled_cluster_log_types" {
  type        = list(string)
  description = "EKS control-plane log types sent to CloudWatch. Empty disables control-plane logging."
  default     = ["api", "audit"]
}

variable "log_retention_days" {
  type        = number
  description = "CloudWatch retention for EKS control-plane logs."
  default     = 14
}

variable "node_instance_types" {
  type        = list(string)
  description = "Managed node group instance types. Dev/example sizing, not production sizing."
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
  type        = number
  description = "Managed node root volume size in GiB."
  default     = 20
}

variable "node_ami_type" {
  type        = string
  description = "EKS optimized AMI type for managed nodes."
  default     = "AL2023_x86_64_STANDARD"
}

variable "node_capacity_type" {
  type    = string
  default = "ON_DEMAND"
}

variable "tags" {
  type = map(string)
}
