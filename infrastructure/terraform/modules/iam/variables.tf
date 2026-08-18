variable "name_prefix" {
  type        = string
  description = "Resource name prefix."
}

variable "cluster_name" {
  type        = string
  description = "EKS cluster name for Pod Identity association."
}

variable "cluster_arn" {
  type        = string
  description = "EKS cluster ARN used to scope the Pod Identity trust policy."
}

variable "alb_controller_namespace" {
  type    = string
  default = "kube-system"
}

variable "alb_controller_service_account" {
  type    = string
  default = "aws-load-balancer-controller"
}

variable "tags" {
  type = map(string)
}
