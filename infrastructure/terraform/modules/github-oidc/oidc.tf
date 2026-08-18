data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

# Account-level GitHub Actions OIDC provider. AWS accounts may have only one
# provider for this URL. Set create_oidc_provider=false if it already exists.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  tags           = var.tags
}

locals {
  github_oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
  github_oidc_provider_url = "token.actions.githubusercontent.com"

  ecr_publish_subs = [
    "repo:${var.github_repository}:ref:refs/heads/main",
    "repo:${var.github_repository}:ref:refs/tags/v*",
  ]

  terraform_plan_subs = [
    "repo:${var.github_repository}:ref:refs/heads/main",
  ]

  deploy_subs = [
    "repo:${var.github_repository}:environment:${var.github_environment}",
  ]
}
