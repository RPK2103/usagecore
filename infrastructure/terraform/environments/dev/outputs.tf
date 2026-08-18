output "account_id" {
  value = data.aws_caller_identity.current.account_id
}

output "aws_region" {
  value = var.aws_region
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "public_subnet_ids" {
  value = module.network.public_subnet_ids
}

output "private_app_subnet_ids" {
  value = module.network.private_app_subnet_ids
}

output "private_data_subnet_ids" {
  value = module.network.private_data_subnet_ids
}

output "nat_gateway_strategy" {
  value = module.network.nat_gateway_strategy
}

output "eks_cluster_name" {
  value = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "eks_cluster_version" {
  value = module.eks.cluster_version
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "rds_jdbc_url" {
  description = "Maps to USAGECORE_DB_URL. Username/password come from the RDS-managed Secrets Manager secret, not this output."
  value       = module.rds.jdbc_url
}

output "rds_master_user_secret_arn" {
  value     = module.rds.master_user_secret_arn
  sensitive = true
}

output "msk_bootstrap_brokers_sasl_scram" {
  description = "Maps to USAGECORE_KAFKA_BOOTSTRAP_SERVERS after SASL/SCRAM client configuration is applied at deploy time."
  value       = module.msk.bootstrap_brokers_sasl_scram
}

output "msk_scram_secret_arn" {
  value     = module.secrets.msk_scram_secret_arn
  sensitive = true
}

output "oidc_client_secret_arn" {
  value     = module.secrets.oidc_client_secret_arn
  sensitive = true
}

output "alb_security_group_id" {
  value = module.network.alb_security_group_id
}

output "alb_controller_role_arn" {
  value = module.iam.alb_controller_role_arn
}

output "acm_certificate_arn" {
  value = var.acm_certificate_arn
}

output "helm_image_tag" {
  value = var.image_tag
}

output "github_oidc_provider_arn" {
  value = module.github_oidc.oidc_provider_arn
}

output "github_ecr_publish_role_arn" {
  value = module.github_oidc.ecr_publish_role_arn
}

output "github_terraform_plan_role_arn" {
  value = module.github_oidc.terraform_plan_role_arn
}

output "github_terraform_apply_role_arn" {
  value = module.github_oidc.terraform_apply_role_arn
}

output "github_helm_deploy_role_arn" {
  value = module.github_oidc.helm_deploy_role_arn
}

output "github_oidc_subjects" {
  value = {
    ecr_publish    = module.github_oidc.oidc_subject_ecr_publish
    terraform_plan = module.github_oidc.oidc_subject_terraform_plan
    deploy         = module.github_oidc.oidc_subject_deploy
    repository     = module.github_oidc.github_repository
    environment    = module.github_oidc.github_environment
  }
}
