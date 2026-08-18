output "alb_controller_role_arn" {
  value = aws_iam_role.alb_controller.arn
}

output "alb_controller_service_account" {
  value = var.alb_controller_service_account
}

output "alb_controller_namespace" {
  value = var.alb_controller_namespace
}
