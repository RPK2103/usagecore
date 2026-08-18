data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
data "aws_partition" "current" {}

# Secret container only. Terraform does not write a secret version, so the Kafka
# password is not placed in Terraform state by this module. An operator (or Phase 14)
# must put {"username":"...","password":"..."} before enabling MSK secret association.
resource "aws_secretsmanager_secret" "msk_scram" {
  name        = "AmazonMSK_${var.name_prefix}-scram"
  description = "MSK SASL/SCRAM credentials for UsageCore. Value is not managed by Terraform."
  tags        = var.tags
}

data "aws_iam_policy_document" "msk_scram_resource" {
  statement {
    sid    = "AWSKafkaResourcePolicy"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["kafka.amazonaws.com"]
    }

    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
    ]

    resources = [aws_secretsmanager_secret.msk_scram.arn]

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values = [
        "arn:${data.aws_partition.current.partition}:kafka:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:cluster/${var.name_prefix}-kafka/*",
      ]
    }
  }
}

resource "aws_secretsmanager_secret_policy" "msk_scram" {
  secret_arn = aws_secretsmanager_secret.msk_scram.arn
  policy     = data.aws_iam_policy_document.msk_scram_resource.json
}

# Placeholder for an externally hosted OIDC client secret if one is used.
# Terraform does not write the value.
resource "aws_secretsmanager_secret" "oidc_client" {
  name        = "${var.name_prefix}/oidc-client"
  description = "Optional OIDC client secret for an externally hosted issuer. Value is not managed by Terraform."
  tags        = var.tags
}
