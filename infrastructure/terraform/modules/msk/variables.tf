variable "name_prefix" {
  type        = string
  description = "Resource name prefix."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private data subnet IDs. Broker count must be a multiple of this list length."
}

variable "security_group_ids" {
  type        = list(string)
  description = "Security groups attached to MSK brokers."
}

variable "kafka_version" {
  type        = string
  description = "MSK Kafka version string, for example 3.8.x. Aligns with local Apache Kafka 3.8.x used in Compose/kind."
  default     = "3.8.x"
}

variable "broker_instance_type" {
  type        = string
  description = "Dev/example broker size, not production sizing. kafka.t3.small is a cost-conscious development class."
  default     = "kafka.t3.small"
}

variable "broker_count" {
  type        = number
  description = "Must be a multiple of the number of client subnets. Two brokers in two AZs is the cost-conscious default."

  validation {
    condition     = var.broker_count >= 2
    error_message = "MSK provisioned clusters require at least two brokers."
  }
}

variable "broker_volume_size" {
  type        = number
  description = "EBS volume size per broker in GiB."
  default     = 20
}

variable "encryption_at_rest_kms_key_arn" {
  type        = string
  description = "KMS key for MSK at-rest encryption. Default is the AWS-managed kafka alias, not a customer CMK."
  default     = "alias/aws/kafka"
}

variable "scram_secret_arns" {
  type        = list(string)
  description = "Secrets Manager ARNs to associate for SASL/SCRAM. Empty until operators populate the secret value."
  default     = []
}

variable "tags" {
  type = map(string)
}
