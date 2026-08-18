data "aws_iam_policy_document" "ecr_publish_trust" {
  statement {
    sid     = "GitHubOidcEcrPublish"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "${local.github_oidc_provider_url}:sub"
      values   = local.ecr_publish_subs
    }
  }
}

data "aws_iam_policy_document" "terraform_plan_trust" {
  statement {
    sid     = "GitHubOidcTerraformPlan"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "${local.github_oidc_provider_url}:sub"
      values   = local.terraform_plan_subs
    }
  }
}

data "aws_iam_policy_document" "terraform_apply_trust" {
  statement {
    sid     = "GitHubOidcTerraformApply"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_provider_url}:sub"
      values   = local.deploy_subs
    }
  }
}

data "aws_iam_policy_document" "helm_deploy_trust" {
  statement {
    sid     = "GitHubOidcHelmDeploy"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_provider_url}:sub"
      values   = local.deploy_subs
    }
  }
}

resource "aws_iam_role" "ecr_publish" {
  name               = "${var.name_prefix}-gha-ecr-publish"
  assume_role_policy = data.aws_iam_policy_document.ecr_publish_trust.json
  tags               = var.tags
}

resource "aws_iam_role" "terraform_plan" {
  name               = "${var.name_prefix}-gha-tf-plan"
  assume_role_policy = data.aws_iam_policy_document.terraform_plan_trust.json
  tags               = var.tags
}

resource "aws_iam_role" "terraform_apply" {
  name               = "${var.name_prefix}-gha-tf-apply"
  assume_role_policy = data.aws_iam_policy_document.terraform_apply_trust.json
  tags               = var.tags
}

resource "aws_iam_role" "helm_deploy" {
  name               = "${var.name_prefix}-gha-helm-deploy"
  assume_role_policy = data.aws_iam_policy_document.helm_deploy_trust.json
  tags               = var.tags
}
