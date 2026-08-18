output "oidc_provider_arn" {
  value = local.github_oidc_provider_arn
}

output "ecr_publish_role_arn" {
  value = aws_iam_role.ecr_publish.arn
}

output "terraform_plan_role_arn" {
  value = aws_iam_role.terraform_plan.arn
}

output "terraform_apply_role_arn" {
  value = aws_iam_role.terraform_apply.arn
}

output "helm_deploy_role_arn" {
  value = aws_iam_role.helm_deploy.arn
}

output "github_repository" {
  value = var.github_repository
}

output "github_environment" {
  value = var.github_environment
}

output "oidc_subject_ecr_publish" {
  value = local.ecr_publish_subs
}

output "oidc_subject_terraform_plan" {
  value = local.terraform_plan_subs
}

output "oidc_subject_deploy" {
  value = local.deploy_subs
}
