variable "name_prefix" {
  type        = string
  description = "Resource name prefix, for example usagecore-dev."
}

variable "cluster_name" {
  type        = string
  description = "EKS cluster name used for kubernetes.io/cluster subnet tags required by the AWS Load Balancer Controller."
}

variable "vpc_cidr" {
  type        = string
  description = "VPC IPv4 CIDR. Must not overlap operator home/office networks if those will be routed in."

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR, for example 10.20.0.0/16."
  }
}

variable "availability_zones" {
  type        = list(string)
  description = "Availability zones used for public, private application, and private data subnets."

  validation {
    condition     = length(var.availability_zones) >= 2 && length(var.availability_zones) <= 3
    error_message = "Use 2 or 3 availability zones."
  }
}

variable "nat_gateway_strategy" {
  type        = string
  description = "single = one NAT Gateway (dev cost default). per_az = one NAT per public subnet (higher availability, higher cost)."

  validation {
    condition     = contains(["single", "per_az"], var.nat_gateway_strategy)
    error_message = "nat_gateway_strategy must be single or per_az."
  }
}

variable "enable_s3_gateway_endpoint" {
  type        = bool
  description = "Create a free S3 gateway endpoint so ECR image layers and other S3 traffic can avoid NAT."
  default     = true
}

variable "alb_ingress_cidrs" {
  type        = list(string)
  description = "CIDRs allowed to reach the internet-facing ALB. Defaults to the public Internet."
  default     = ["0.0.0.0/0"]

  validation {
    condition     = alltrue([for cidr in var.alb_ingress_cidrs : can(cidrhost(cidr, 0))])
    error_message = "Each alb_ingress_cidrs value must be a valid IPv4 CIDR."
  }
}

variable "alb_http_enabled" {
  type        = bool
  description = "Allow TCP 80 on the ALB security group. Dev convenience when ACM certificates are not available; production should use 443 only."
  default     = true
}

variable "alb_https_enabled" {
  type        = bool
  description = "Allow TCP 443 on the ALB security group."
  default     = true
}

variable "app_ports" {
  type        = list(number)
  description = "Workload container ports that the ALB may target."
  default     = [8080, 8082, 8083]
}

variable "tags" {
  type        = map(string)
  description = "Tags applied to network resources."
}
