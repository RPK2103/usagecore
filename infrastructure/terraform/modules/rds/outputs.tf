output "endpoint" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "username" {
  value = aws_db_instance.this.username
}

output "jdbc_url" {
  value = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}"
}

output "master_user_secret_arn" {
  description = "Secrets Manager ARN created by RDS managed master password. The secret value is not output."
  value       = try(aws_db_instance.this.master_user_secret[0].secret_arn, null)
  sensitive   = true
}

output "instance_id" {
  value = aws_db_instance.this.id
}

output "multi_az" {
  value = aws_db_instance.this.multi_az
}
