data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name_prefix = "usagecore-${var.environment}"

  availability_zones = length(var.availability_zones) > 0 ? var.availability_zones : slice(data.aws_availability_zones.available.names, 0, var.az_count)

  tags = {
    Project     = "UsageCore"
    Environment = var.environment
    ManagedBy   = "Terraform"
    Repository  = "UsageCore"
  }

  msk_subnet_ids = slice(module.network.private_data_subnet_ids, 0, var.msk_broker_count)
}

check "eks_public_endpoint_not_world_open" {
  assert {
    condition     = var.eks_allow_public_endpoint_from_anywhere || !contains(var.eks_public_access_cidrs, "0.0.0.0/0")
    error_message = "EKS public API 0.0.0.0/0 is refused unless eks_allow_public_endpoint_from_anywhere is true."
  }
}

check "msk_broker_count_matches_subnets" {
  assert {
    condition     = var.msk_broker_count == length(local.msk_subnet_ids)
    error_message = "MSK broker count must equal the number of selected data subnets."
  }
}

module "network" {
  source = "../../modules/network"

  name_prefix          = local.name_prefix
  cluster_name         = var.eks_cluster_name
  vpc_cidr             = var.vpc_cidr
  availability_zones   = local.availability_zones
  nat_gateway_strategy = var.nat_gateway_strategy
  alb_http_enabled     = var.alb_http_enabled
  alb_https_enabled    = var.alb_https_enabled
  alb_ingress_cidrs    = var.alb_ingress_cidrs
  tags                 = local.tags
}

module "eks" {
  source = "../../modules/eks"

  name_prefix                   = local.name_prefix
  cluster_name                  = var.eks_cluster_name
  kubernetes_version            = var.kubernetes_version
  subnet_ids                    = module.network.private_app_subnet_ids
  additional_security_group_ids = [module.network.eks_nodes_security_group_id]
  endpoint_private_access       = true
  endpoint_public_access        = var.eks_endpoint_public_access
  public_access_cidrs           = var.eks_public_access_cidrs
  node_instance_types           = var.node_instance_types
  node_desired_size             = var.node_desired_size
  node_min_size                 = var.node_min_size
  node_max_size                 = var.node_max_size
  node_disk_size                = var.node_disk_size
  tags                          = local.tags
}

module "ecr" {
  source = "../../modules/ecr"

  force_delete = var.ecr_force_delete
  tags         = local.tags
}

module "rds" {
  source = "../../modules/rds"

  name_prefix             = local.name_prefix
  subnet_ids              = module.network.private_data_subnet_ids
  security_group_ids      = [module.network.rds_security_group_id]
  engine_version          = var.rds_engine_version
  instance_class          = var.rds_instance_class
  allocated_storage       = var.rds_allocated_storage
  multi_az                = var.rds_multi_az
  backup_retention_period = var.rds_backup_retention_period
  deletion_protection     = var.rds_deletion_protection
  skip_final_snapshot     = var.rds_skip_final_snapshot
  tags                    = local.tags
}

module "secrets" {
  source = "../../modules/secrets"

  name_prefix = local.name_prefix
  tags        = local.tags
}

module "msk" {
  source = "../../modules/msk"

  name_prefix          = local.name_prefix
  subnet_ids           = local.msk_subnet_ids
  security_group_ids   = [module.network.msk_security_group_id]
  kafka_version        = var.msk_kafka_version
  broker_instance_type = var.msk_broker_instance_type
  broker_count         = var.msk_broker_count
  broker_volume_size   = var.msk_broker_volume_size
  scram_secret_arns    = var.msk_associate_scram_secret ? [module.secrets.msk_scram_secret_arn] : []
  tags                 = local.tags
}

module "iam" {
  source = "../../modules/iam"

  name_prefix  = local.name_prefix
  cluster_name = var.eks_cluster_name
  cluster_arn  = module.eks.cluster_arn
  tags         = local.tags
}
