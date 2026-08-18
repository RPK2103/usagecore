# ECR publish is scoped to the three UsageCore repositories.
# Wildcard audit:
# - ecr:GetAuthorizationToken requires Resource="*" (AWS API constraint).
# - No ecr:DeleteRepository, ecr:BatchDeleteImage, or repository-policy mutation.

data "aws_iam_policy_document" "ecr_publish" {
  statement {
    sid       = "EcrAuthorizationToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPushThreeRepositories"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
      "ecr:ListImages",
    ]
    resources = var.ecr_repository_arns
  }
}

resource "aws_iam_policy" "ecr_publish" {
  name   = "${var.name_prefix}-gha-ecr-publish"
  policy = data.aws_iam_policy_document.ecr_publish.json
  tags   = var.tags
}

resource "aws_iam_role_policy_attachment" "ecr_publish" {
  role       = aws_iam_role.ecr_publish.name
  policy_arn = aws_iam_policy.ecr_publish.arn
}

# Plan needs broad read plus state lock writes. ReadOnlyAccess is the honest
# choice for terraform plan -refresh; a fake least-privilege list would miss
# provider Describe APIs and produce unusable plans.
data "aws_iam_policy_document" "terraform_state" {
  count = var.state_bucket_arn == "" ? 0 : 1

  statement {
    sid    = "RemoteStateBucket"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:ListBucket",
      "s3:GetBucketVersioning",
      "s3:GetEncryptionConfiguration",
    ]
    resources = [
      var.state_bucket_arn,
      "${var.state_bucket_arn}/*",
    ]
  }
}

resource "aws_iam_policy" "terraform_state" {
  count  = var.state_bucket_arn == "" ? 0 : 1
  name   = "${var.name_prefix}-gha-tf-state"
  policy = data.aws_iam_policy_document.terraform_state[0].json
  tags   = var.tags
}

resource "aws_iam_role_policy_attachment" "terraform_plan_readonly" {
  role       = aws_iam_role.terraform_plan.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_plan_state" {
  count      = var.state_bucket_arn == "" ? 0 : 1
  role       = aws_iam_role.terraform_plan.name
  policy_arn = aws_iam_policy.terraform_state[0].arn
}

# Apply is infrastructure management for this stack. It is not AdministratorAccess.
# Wildcard audit is in docs/cicd/security.md and ADR-023.
data "aws_iam_policy_document" "terraform_apply" {
  statement {
    sid    = "ManageUsageCoreNetworkAndCompute"
    effect = "Allow"
    actions = [
      "ec2:*",
      "eks:*",
      "elasticloadbalancing:Describe*",
      "logs:CreateLogGroup",
      "logs:DeleteLogGroup",
      "logs:DescribeLogGroups",
      "logs:ListTagsForResource",
      "logs:PutRetentionPolicy",
      "logs:TagResource",
      "logs:UntagResource",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "ManageUsageCoreDataPlane"
    effect = "Allow"
    actions = [
      "rds:*",
      "kafka:*",
      "secretsmanager:*",
      "ecr:*",
    ]
    resources = ["*"]
  }

  statement {
    sid    = "ManageUsageCoreIamRoles"
    effect = "Allow"
    actions = [
      "iam:AddRoleToInstanceProfile",
      "iam:AttachRolePolicy",
      "iam:CreateOpenIDConnectProvider",
      "iam:CreatePolicy",
      "iam:CreatePolicyVersion",
      "iam:CreateRole",
      "iam:CreateServiceLinkedRole",
      "iam:DeleteOpenIDConnectProvider",
      "iam:DeletePolicy",
      "iam:DeletePolicyVersion",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:DetachRolePolicy",
      "iam:GetOpenIDConnectProvider",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:GetRole",
      "iam:GetRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole",
      "iam:ListOpenIDConnectProviders",
      "iam:ListPolicyVersions",
      "iam:ListRolePolicies",
      "iam:ListRoles",
      "iam:PassRole",
      "iam:PutRolePolicy",
      "iam:RemoveRoleFromInstanceProfile",
      "iam:TagOpenIDConnectProvider",
      "iam:TagPolicy",
      "iam:TagRole",
      "iam:UntagOpenIDConnectProvider",
      "iam:UntagPolicy",
      "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy",
      "iam:UpdateOpenIDConnectProviderThumbprint",
      "iam:UpdateRole",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "terraform_apply" {
  name   = "${var.name_prefix}-gha-tf-apply"
  policy = data.aws_iam_policy_document.terraform_apply.json
  tags   = var.tags
}

resource "aws_iam_role_policy_attachment" "terraform_apply" {
  role       = aws_iam_role.terraform_apply.name
  policy_arn = aws_iam_policy.terraform_apply.arn
}

resource "aws_iam_role_policy_attachment" "terraform_apply_state" {
  count      = var.state_bucket_arn == "" ? 0 : 1
  role       = aws_iam_role.terraform_apply.name
  policy_arn = aws_iam_policy.terraform_state[0].arn
}

data "aws_iam_policy_document" "helm_deploy" {
  statement {
    sid    = "EksKubeconfig"
    effect = "Allow"
    actions = [
      "eks:DescribeCluster",
      "eks:ListClusters",
      "eks:AccessKubernetesApi",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:eks:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:cluster/${var.eks_cluster_name}",
    ]
  }

  statement {
    sid       = "EcrAuthorizationToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPullForDeployVerify"
    effect = "Allow"
    actions = [
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = var.ecr_repository_arns
  }

  statement {
    sid    = "ReadRuntimeSecrets"
    effect = "Allow"
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:rds!db-*",
      "arn:${data.aws_partition.current.partition}:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:AmazonMSK_${var.name_prefix}-scram*",
    ]
  }
}

resource "aws_iam_policy" "helm_deploy" {
  name   = "${var.name_prefix}-gha-helm-deploy"
  policy = data.aws_iam_policy_document.helm_deploy.json
  tags   = var.tags
}

resource "aws_iam_role_policy_attachment" "helm_deploy" {
  role       = aws_iam_role.helm_deploy.name
  policy_arn = aws_iam_policy.helm_deploy.arn
}
