output "msk_scram_secret_arn" {
  value     = aws_secretsmanager_secret.msk_scram.arn
  sensitive = true
}

output "msk_scram_secret_name" {
  value = aws_secretsmanager_secret.msk_scram.name
}

output "oidc_client_secret_arn" {
  value     = aws_secretsmanager_secret.oidc_client.arn
  sensitive = true
}
